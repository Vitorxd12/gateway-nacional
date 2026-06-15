package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config.IntelProperties;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Empresa;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository.EmpresaRepository;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.service.ProspeccaoRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Carga dirigida de validação no startup (M3 do plano): popula o store com uma
 * amostragem de um município e imprime uma amostra das empresas enriquecidas
 * para conferir CNAE + IBGE na prática. Default: Aracaju ({@code 2800308}).
 *
 * <p>Existe apenas quando o módulo está ligado (conditional). O gatilho real é
 * checado em runtime ({@code seed.enabled}) — assim evitamos a combinação
 * inválida "seed on, módulo off" que quebraria o contexto.</p>
 *
 * <p>OPT-IN: setar {@code GATEWAY_LIC_INTEL_SEED_ENABLED=true}. Roda uma vez.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class AracajuSeedRunner implements ApplicationRunner {

    private static final int AMOSTRA = 15;

    /** Nº máx de lotes de backfill rodados no seed para validação. */
    private static final int MAX_LOTES_BACKFILL = 6;

    private final ParticipacaoIngestionService ingestion;
    private final EmpresaBackfillWorker backfill;
    private final ProspeccaoRefreshService refresh;
    private final EmpresaRepository empresaRepo;
    private final IntelProperties props;

    public AracajuSeedRunner(ParticipacaoIngestionService ingestion,
                             EmpresaBackfillWorker backfill,
                             ProspeccaoRefreshService refresh,
                             EmpresaRepository empresaRepo,
                             IntelProperties props) {
        this.ingestion = ingestion;
        this.backfill = backfill;
        this.refresh = refresh;
        this.empresaRepo = empresaRepo;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        IntelProperties.Seed seed = props.seed();
        if (seed == null || !seed.enabled()) {
            return; // seed desligado — operação normal
        }
        String municipio = seed.municipioIbge();
        int janela = seed.janelaDias();
        log.info("[LIC-INTEL][SEED] Carga dirigida iniciando — municipio={} janela={}d", municipio, janela);

        // O seed NUNCA pode derrubar o boot do gateway — um erro de rede/PNCP
        // aqui é logado e engolido; a aplicação continua servindo normalmente.
        try {
            var resumo = ingestion.ingerirMunicipioJanela(municipio, janela);
            log.info("[LIC-INTEL][SEED] Ingestão (stubs): {} contratações, {} participações, {} empresas distintas.",
                    resumo.contratacoes(), resumo.participacoes(), resumo.empresas());

            // M2.5: roda alguns lotes de backfill em foreground para já validar o
            // enriquecimento (em produção o EmpresaBackfillWorker faz isso no cron).
            int pendentesAntes = empresaRepo.countPendentes();
            for (int i = 0; i < MAX_LOTES_BACKFILL && empresaRepo.countPendentes() > 0; i++) {
                int ok = backfill.rodarUmLote();
                log.info("[LIC-INTEL][SEED] Backfill lote {}: {} enriquecidos, {} pendentes.",
                        i + 1, ok, empresaRepo.countPendentes());
                if (ok == 0) {
                    break; // lote inteiro falhou (throttle) — para de insistir no seed
                }
            }
            log.info("[LIC-INTEL][SEED] Backfill: pendentes {} -> {}.",
                    pendentesAntes, empresaRepo.countPendentes());

            // Atualiza o read-model para o endpoint /v1/inteligencia/prospeccao
            // já refletir os dados recém-ingeridos+enriquecidos.
            refresh.refrescar();

            // Amostra de validação: confirma que CNAE e IBGE foram preenchidos.
            List<Empresa> amostra = empresaRepo.amostra(AMOSTRA);
            if (amostra.isEmpty()) {
                log.warn("[LIC-INTEL][SEED] Nenhuma empresa gravada — verifique os endpoints do PNCP "
                        + "(ver avisos no PncpResultadosClient) e a conectividade.");
                return;
            }
            log.info("[LIC-INTEL][SEED] Amostra de empresas enriquecidas (cnpj | cnae | ibge | uf | razão):");
            for (Empresa e : amostra) {
                log.info("[LIC-INTEL][SEED]   {} | cnae={} | ibge={} | {} | {}",
                        e.cnpj(), e.cnaePrincipal(), e.municipioIbge(), e.uf(), e.razaoSocial());
            }
        } catch (RuntimeException ex) {
            log.error("[LIC-INTEL][SEED] Carga dirigida falhou (ignorada, app segue): {}", ex.toString(), ex);
        }
    }
}
