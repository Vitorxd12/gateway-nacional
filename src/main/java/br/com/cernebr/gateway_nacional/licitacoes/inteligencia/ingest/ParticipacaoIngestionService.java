package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import br.com.cernebr.gateway_nacional.cadastral.cep.service.IbgeEnrichmentService;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config.IntelProperties;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Licitacao;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Papel;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Participacao;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository.EmpresaRepository;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository.LicitacaoIntelRepository;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository.ParticipacaoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Orquestra a ingestão do grafo de participação a partir do PNCP.
 *
 * <p>Fluxo por contratação: grava o snapshot {@code licitacao} → busca os
 * resultados (fornecedores) → garante cada empresa enriquecida → grava as
 * {@code participacao}. As empresas são enriquecidas ANTES da transação de
 * licitação+participação para satisfazer a FK {@code participacao→empresa}.</p>
 *
 * <p>Idempotente de ponta a ponta (todos os repositórios fazem upsert), então
 * reprocessar a mesma janela atualiza em vez de duplicar.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class ParticipacaoIngestionService {

    private static final DateTimeFormatter PNCP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneOffset BR = ZoneOffset.of("-03:00");
    private static final int MAX_PAGINAS = 20; // guarda contra paginação infinita

    private final PncpResultadosClient pncp;
    private final EmpresaRepository empresaRepo;
    private final LicitacaoIntelRepository licRepo;
    private final ParticipacaoRepository partRepo;
    private final SetorResolver setor;
    private final IbgeEnrichmentService ibge;
    private final TransactionTemplate tx;
    private final IntelProperties props;

    public ParticipacaoIngestionService(PncpResultadosClient pncp,
                                        EmpresaRepository empresaRepo,
                                        LicitacaoIntelRepository licRepo,
                                        ParticipacaoRepository partRepo,
                                        SetorResolver setor,
                                        IbgeEnrichmentService ibge,
                                        @Qualifier("licIntelTxTemplate") TransactionTemplate tx,
                                        IntelProperties props) {
        this.pncp = pncp;
        this.empresaRepo = empresaRepo;
        this.licRepo = licRepo;
        this.partRepo = partRepo;
        this.setor = setor;
        this.ibge = ibge;
        this.tx = tx;
        this.props = props;
    }

    /** Resumo de uma rodada de ingestão — para log de auditoria. */
    public record ResumoIngestao(int contratacoes, int participacoes, int empresas) {
    }

    /**
     * Varre as contratações publicadas no município nos últimos {@code janelaDias}
     * dias (todas as modalidades configuradas) e ingere cada uma.
     */
    public ResumoIngestao ingerirMunicipioJanela(String municipioIbge, int janelaDias) {
        LocalDate hoje = LocalDate.now(BR);
        String dataFinal = hoje.format(PNCP_DATE);
        String dataInicial = hoje.minusDays(Math.max(1, janelaDias)).format(PNCP_DATE);
        List<Integer> modalidades = props.ingestao().modalidadeCodigos();

        int contratacoes = 0;
        int participacoes = 0;
        Set<String> empresas = new HashSet<>();

        log.info("[LIC-INTEL] Ingestão municipio={} janela={}..{} modalidades={}",
                municipioIbge, dataInicial, dataFinal, modalidades);

        for (Integer modalidade : modalidades) {
            for (int pagina = 1; pagina <= MAX_PAGINAS; pagina++) {
                List<PncpContratacaoResumo> lote;
                try {
                    lote = pncp.listarContratacoesPorMunicipio(municipioIbge, dataInicial, dataFinal, modalidade, pagina);
                } catch (RuntimeException ex) {
                    // Timeout/IO do PNCP não pode abortar o job — pula a modalidade.
                    log.warn("[LIC-INTEL] Falha ao listar modalidade={} pagina={}: {}", modalidade, pagina, ex.toString());
                    break;
                }
                if (lote.isEmpty()) {
                    break; // fim da paginação para esta modalidade
                }
                for (PncpContratacaoResumo c : lote) {
                    try {
                        participacoes += ingerir(c, empresas);
                        contratacoes++;
                    } catch (RuntimeException ex) {
                        // Uma contratação problemática não derruba a varredura.
                        log.warn("[LIC-INTEL] Falha ao ingerir {}: {}", c.identificador(), ex.toString());
                    }
                    throttle();
                }
            }
        }
        ResumoIngestao resumo = new ResumoIngestao(contratacoes, participacoes, empresas.size());
        log.info("[LIC-INTEL] Ingestão concluída: {}", resumo);
        return resumo;
    }

    /**
     * Ingere uma contratação: snapshot + resultados → empresas → participações.
     * Devolve o nº de participações gravadas. {@code empresasAcc} acumula os
     * CNPJs distintos vistos na rodada.
     */
    public int ingerir(PncpContratacaoResumo c, Set<String> empresasAcc) {
        if (c.cnpjOrgao() == null || c.anoCompra() == null || c.sequencialCompra() == null) {
            return 0;
        }
        int ano = c.anoCompra();
        String municipioIbge = c.municipioIbge() != null
                ? c.municipioIbge()
                : ibge.resolveIbge(c.orgaoUf(), c.municipioNome());

        Licitacao lic = new Licitacao(
                null, "comprasnet", c.identificador(), c.numeroCompra(),
                c.objetoCompra(), c.modalidadeNome(), setor.fromObjeto(c.objetoCompra()),
                c.cnpjOrgao(), c.orgaoNome(), c.orgaoUf(), c.municipioNome(), municipioIbge,
                c.valorTotalEstimado(), c.valorTotalHomologado(),
                c.dataAberturaProposta(),
                c.dataEncerramentoProposta(), // proxy de data_resultado até PNCP expor a homologação
                ano, c.situacaoCompraNome(), "pncp", null);

        List<PncpResultadoFornecedor> resultados =
                pncp.buscarResultados(c.cnpjOrgao(), ano, c.sequencialCompra());

        // Enriquece empresas FORA da transação de licitação/participação (cada
        // upsert de empresa tem a própria tx) — garante a FK antes do insert.
        List<Participacao> rows = new ArrayList<>();
        for (PncpResultadoFornecedor r : resultados) {
            String cnpj = Cnpjs.normalizar(r.niFornecedor());
            if (cnpj == null) {
                continue; // não-PJ (CPF) — fora de escopo
            }
            // M2.5: grava só o stub (rápido). CNAE/IBGE vêm depois pelo worker.
            empresaRepo.insertStubIfAbsent(cnpj, r.nomeRazaoSocialFornecedor());
            empresasAcc.add(cnpj);
            // data_resultado real do PNCP quando presente; fallback no encerramento.
            var dataResultado = r.dataResultado() != null ? r.dataResultado() : c.dataEncerramentoProposta();
            rows.add(new Participacao(
                    null, 0L, cnpj, papel(r), r.numeroItem(), r.ordemClassificacao(),
                    null, r.valorTotalHomologado(), dataResultado, ano, "pncp", null));
        }

        tx.executeWithoutResult(s -> {
            long licId = licRepo.upsert(lic);
            if (!rows.isEmpty()) {
                List<Participacao> comId = new ArrayList<>(rows.size());
                for (Participacao p : rows) {
                    comId.add(new Participacao(
                            p.id(), licId, p.empresaCnpj(), p.papel(), p.itemSequencial(), p.classificacao(),
                            p.valorProposta(), p.valorHomologado(), p.dataResultado(), p.ano(), p.fonte(),
                            p.ingeridoEm()));
                }
                partRepo.upsertBatch(comId);
            }
        });
        return rows.size();
    }

    /**
     * O endpoint {@code /resultados} do PNCP lista os fornecedores com resultado
     * informado por item — ou seja, HOMOLOGADOS (têm {@code valorTotalHomologado}).
     * Não são meros proponentes (isso viria de {@code /propostas}, não ingerido).
     */
    private static Papel papel(PncpResultadoFornecedor r) {
        String s = r.situacaoNome() == null ? "" : r.situacaoNome().toLowerCase(Locale.ROOT);
        if (s.contains("cancel") || s.contains("desclass") || s.contains("anulad") || s.contains("revog")) {
            return Papel.DESCLASSIFICADO;
        }
        return r.valorTotalHomologado() != null ? Papel.HOMOLOGADO : Papel.VENCEDOR;
    }

    private void throttle() {
        int rps = props.ingestao().rateLimitRps();
        if (rps <= 0) {
            return;
        }
        try {
            Thread.sleep(1000L / rps);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
