package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config.IntelProperties;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository.EmpresaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Worker assíncrono de enriquecimento (M2.5). Desacopla o lookup pesado de CNPJ
 * (que sofre throttle dos providers) da ingestão do PNCP: a ingestão grava só o
 * stub; este worker, em lotes pequenos com delay calibrado, preenche CNAE/IBGE
 * das empresas com {@code cnae_principal IS NULL}.
 *
 * <p>O {@code delay-ms} entre lookups é o rate-limiter — evita a rajada que
 * derrubava ~80% dos enriquecimentos quando feitos inline. Falhas não travam a
 * fila: {@code EmpresaEnrichmentService.enriquecer} marca a tentativa e o
 * {@code findPendentes} rotaciona para o próximo CNPJ.</p>
 *
 * <p>Conditional ao flag do módulo; o gatilho fino {@code enriquecimento.enabled}
 * é checado em runtime — permite ligar o módulo com o backfill desligado.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class EmpresaBackfillWorker {

    private final EmpresaRepository empresaRepo;
    private final EmpresaEnrichmentService enrichment;
    private final IntelProperties props;

    public EmpresaBackfillWorker(EmpresaRepository empresaRepo,
                                 EmpresaEnrichmentService enrichment,
                                 IntelProperties props) {
        this.empresaRepo = empresaRepo;
        this.enrichment = enrichment;
        this.props = props;
    }

    @Scheduled(cron = "${gateway.licitacoes.inteligencia.enriquecimento.cron}")
    public void rodar() {
        IntelProperties.Enriquecimento cfg = props.enriquecimento();
        if (cfg == null || !cfg.enabled()) {
            return;
        }
        List<String> pendentes = empresaRepo.findPendentes(cfg.batchSize());
        if (pendentes.isEmpty()) {
            return;
        }
        log.info("[LIC-INTEL] Backfill: processando lote de {} (pendentes totais={})",
                pendentes.size(), empresaRepo.countPendentes());
        int ok = 0;
        for (String cnpj : pendentes) {
            if (enrichment.enriquecer(cnpj)) {
                ok++;
            }
            sleep(cfg.delayMs());
        }
        log.info("[LIC-INTEL] Backfill: {}/{} enriquecidos neste lote; restam {} pendentes.",
                ok, pendentes.size(), empresaRepo.countPendentes());
    }

    /** Execução manual do backfill (seed/teste) — devolve quantos enriqueceu. */
    public int rodarUmLote() {
        IntelProperties.Enriquecimento cfg = props.enriquecimento();
        List<String> pendentes = empresaRepo.findPendentes(cfg.batchSize());
        int ok = 0;
        for (String cnpj : pendentes) {
            if (enrichment.enriquecer(cnpj)) {
                ok++;
            }
            sleep(cfg.delayMs());
        }
        return ok;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
