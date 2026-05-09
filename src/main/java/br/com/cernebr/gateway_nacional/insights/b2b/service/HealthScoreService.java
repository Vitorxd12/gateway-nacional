package br.com.cernebr.gateway_nacional.insights.b2b.service;

import br.com.cernebr.gateway_nacional.cadastral.cnae.dto.CnaeResponse;
import br.com.cernebr.gateway_nacional.cadastral.cnae.service.CnaeService;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.service.CnpjService;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.insights.b2b.dto.HealthScoreResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrator do domínio Insights B2B — agrega cadastro Receita Federal
 * (CNPJ), classificação CONCLA (CNAE) e sinal heurístico de setor saúde
 * num único dossiê para onboarding de clínicas no ERP CerneBR.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><b>A — CNPJ (sequencial)</b>: o {@code cnaePrincipal} retornado é
 *       chave de entrada para o passo B, então não há paralelismo aqui;</li>
 *   <li><b>B — CNAE</b> e <b>C — Sinal de saúde</b> rodam em paralelo num
 *       executor de virtual threads. Cada {@code CompletableFuture.supplyAsync}
 *       é cheap (não consome thread de plataforma) e ambas as rotas
 *       internas já têm seus próprios caches Redis e fallbacks em cascata,
 *       então a paralelização economiza puramente latência de rede agregada.</li>
 * </ol>
 *
 * <h2>Sobre o "registro CNES"</h2>
 * O {@code CnesService} atual indexa por {@code cnesBase + ibge} e foi
 * desenhado para atendimento APS — não há lookup por CNPJ no upstream
 * DATASUS exposto pelo gateway hoje. Por isso o sinal de saúde aqui é
 * <i>derivado da divisão CNAE</i>: divisões 86 (atividades de atenção à
 * saúde humana), 87 (atividades de assistência social com alojamento) e
 * 88 (sem alojamento) compõem a Seção Q da CONCLA. O response orienta o
 * ERP a continuar a navegação via CNES manual quando aplicável.
 */
@Slf4j
@Service
public class HealthScoreService {

    private static final String DOMAIN = "insights";
    private static final String AGGREGATOR = "healthScore";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final CnpjService cnpjService;
    private final CnaeService cnaeService;
    private final MeterRegistry meterRegistry;

    public HealthScoreService(CnpjService cnpjService,
                              CnaeService cnaeService,
                              MeterRegistry meterRegistry) {
        this.cnpjService = cnpjService;
        this.cnaeService = cnaeService;
        this.meterRegistry = meterRegistry;
    }

    public HealthScoreResponse buildScore(String cnpj) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            HealthScoreResponse response = doBuildScore(cnpj);
            recordOutcome("success", sample);
            log.info("HealthScore computed for cnpj={} setorSaude={}",
                    cnpj, response.registroSaude().setorSaude());
            return response;
        } catch (RuntimeException ex) {
            recordOutcome("failure", sample);
            log.warn("HealthScore failed for cnpj={}: {}", cnpj, ex.getMessage());
            throw ex;
        }
    }

    private HealthScoreResponse doBuildScore(String cnpj) {
        log.info("HealthScore start cnpj={} (etapa A — CNPJ)", cnpj);
        long t0 = System.currentTimeMillis();
        CnpjResponse empresa = cnpjService.findByCnpj(cnpj);
        long tA = System.currentTimeMillis() - t0;
        log.info("HealthScore etapa A concluída em {}ms — cnaePrincipal={}",
                tA, empresa.cnaePrincipal());

        // B e C em paralelo via virtual threads — try-with-resources
        // garante shutdown determinístico do executor.
        long tBC0 = System.currentTimeMillis();
        HealthScoreResponse.RamoAtividade ramo;
        HealthScoreResponse.RegistroSaude registro;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            log.info("HealthScore etapas B+C disparadas em paralelo (virtual threads)");

            CompletableFuture<HealthScoreResponse.RamoAtividade> fRamo =
                    CompletableFuture.supplyAsync(() -> resolveRamo(empresa.cnaePrincipal()), executor);
            CompletableFuture<HealthScoreResponse.RegistroSaude> fSaude =
                    CompletableFuture.supplyAsync(() -> classifySetor(empresa.cnaePrincipal()), executor);

            ramo = joinUnwrapped(fRamo);
            registro = joinUnwrapped(fSaude);
        }
        long tBC = System.currentTimeMillis() - tBC0;
        log.info("HealthScore etapas B+C concluídas em {}ms (paralelo)", tBC);

        return new HealthScoreResponse(
                new HealthScoreResponse.Empresa(
                        empresa.cnpj(),
                        empresa.razaoSocial(),
                        empresa.nomeFantasia(),
                        empresa.status(),
                        empresa.cep(),
                        empresa.uf(),
                        empresa.municipio()
                ),
                ramo,
                registro
        );
    }

    private HealthScoreResponse.RamoAtividade resolveRamo(String cnaePrincipal) {
        if (cnaePrincipal == null || cnaePrincipal.isBlank()) {
            return new HealthScoreResponse.RamoAtividade(null,
                    "CNAE principal não informado pelo provedor de CNPJ.");
        }
        try {
            CnaeResponse cnae = cnaeService.findByCodigo(cnaePrincipal);
            return new HealthScoreResponse.RamoAtividade(cnae.codigo(), cnae.descricao());
        } catch (ResourceNotFoundException ex) {
            return new HealthScoreResponse.RamoAtividade(cnaePrincipal,
                    "CNAE não encontrado na CONCLA: " + ex.getMessage());
        } catch (ResourceUnavailableException ex) {
            log.warn("CNAE indisponível para {}: {}", cnaePrincipal, ex.getMessage());
            return new HealthScoreResponse.RamoAtividade(cnaePrincipal,
                    "Descrição indisponível (provedores CNAE em falha).");
        }
    }

    /**
     * Heurística de setor baseada na divisão CNAE (2 primeiros dígitos da
     * subclasse de 7 dígitos). A Seção Q da CONCLA = divisões 86/87/88.
     * Em vez de "fingir" uma chamada CNES por CNPJ que o gateway não suporta,
     * o setor é classificado aqui e o ERP recebe orientação clara para o
     * próximo passo de consulta CNES quando aplicável.
     */
    private HealthScoreResponse.RegistroSaude classifySetor(String cnaePrincipal) {
        if (cnaePrincipal == null || cnaePrincipal.length() < 2) {
            return new HealthScoreResponse.RegistroSaude(false, null,
                    "CNAE indisponível — não foi possível classificar setor.");
        }
        String divisao = cnaePrincipal.substring(0, 2);
        return switch (divisao) {
            case "86" -> new HealthScoreResponse.RegistroSaude(true,
                    "Atividades de atenção à saúde humana",
                    "Estabelecimento provável da rede de saúde — consultar "
                            + "/api/v1/saude/cnes/{cnesBase}/profissionais?ibge={ibge} "
                            + "quando o código CNES do estabelecimento estiver disponível.");
            case "87" -> new HealthScoreResponse.RegistroSaude(true,
                    "Atividades de assistência social com alojamento",
                    "Instituição de assistência social com alojamento — verificar "
                            + "registro CNES quando prestar serviço de saúde correlato.");
            case "88" -> new HealthScoreResponse.RegistroSaude(true,
                    "Atividades de assistência social sem alojamento",
                    "Instituição de assistência social sem alojamento — verificar "
                            + "registro CNES quando prestar serviço de saúde correlato.");
            default -> new HealthScoreResponse.RegistroSaude(false, null,
                    "CNAE fora da Seção Q da CONCLA (divisões 86/87/88) — não há "
                            + "expectativa regulatória de registro CNES para este ramo.");
        };
    }

    /**
     * Mesma estratégia do {@code AuditoriaSaudeService}: desembrulha
     * {@link CompletionException} para que a exceção original
     * ({@link ResourceUnavailableException}, {@link ResourceNotFoundException})
     * suba limpa para o {@code GlobalExceptionHandler}.
     */
    private static <T> T joinUnwrapped(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw ce;
        }
    }

    private void recordOutcome(String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", AGGREGATOR)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", AGGREGATOR,
                "outcome", outcome).increment();
    }
}
