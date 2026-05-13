package br.com.cernebr.gateway_nacional.cadastral.cnd.service;

import br.com.cernebr.gateway_nacional.cadastral.cnd.client.FederalCndClient;
import br.com.cernebr.gateway_nacional.cadastral.cnd.client.FgtsCndClient;
import br.com.cernebr.gateway_nacional.cadastral.cnd.client.TstCndClient;
import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndConsolidadaResponse;
import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndFederal;
import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndFgts;
import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndTrabalhista;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orquestrador das três CNDs (Trabalhista, FGTS, Federal) em paralelo
 * usando virtual threads via {@link CompletableFuture#supplyAsync}.
 *
 * <p><b>Por que paralelo:</b> emitir as três certidões em série custaria
 * ~3.6s (TST 800ms + FGTS 1.2s + Federal 1.6s); paralelizadas, o p99 cai
 * para ~1.6s — domínio pelo provedor mais lento. Em ambientes de
 * habilitação de licitação, isso é a diferença entre 5 minutos e 15
 * minutos para um lote típico de 100 fornecedores.</p>
 *
 * <p><b>Degradação graciosa (requisito explícito do produto):</b> diferente
 * do CNPJ/Sintegra (onde provedores são <em>substitutos</em> uns dos outros),
 * aqui cada certidão é <em>independente</em>. A indisponibilidade do nó
 * Caixa <strong>não pode</strong> derrubar a resposta consolidada — o
 * cliente precisa do que estiver disponível para tomar decisão. Por isso,
 * cada {@code CompletableFuture} é resolvido com
 * {@link #fallbackOnFailure(String, Throwable)} via {@code exceptionally},
 * emitindo um sub-record com {@code status=INDISPONIVEL} ao invés de
 * propagar a exceção. O response final carrega {@code degradado=true} e
 * {@code certidoesResolvidas<3} para sinalizar ao consumidor.</p>
 *
 * <p><b>503 só em catástrofe total:</b> apenas se as TRÊS certidões falharem
 * (ex.: queda do executor, problema de rede generalizado), aí sim o
 * service lança 503 — não faz sentido devolver um envelope vazio.</p>
 */
@Slf4j
@Service
public class CndConsolidadaService {

    private static final String DOMAIN = "cnd";
    private static final String CACHE_NAME = "cnd";
    private static final Duration SOFT_TTL = Duration.ofHours(3);

    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final TstCndClient tst;
    private final FgtsCndClient fgts;
    private final FederalCndClient federal;
    private final RefreshAheadCache refreshAheadCache;
    private final MeterRegistry meterRegistry;

    private final ExecutorService parallelExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    public CndConsolidadaService(TstCndClient tst,
                      FgtsCndClient fgts,
                      FederalCndClient federal,
                      RefreshAheadCache refreshAheadCache,
                      MeterRegistry meterRegistry) {
        this.tst = tst;
        this.fgts = fgts;
        this.federal = federal;
        this.refreshAheadCache = refreshAheadCache;
        this.meterRegistry = meterRegistry;
    }

    @PreDestroy
    void shutdown() {
        parallelExecutor.shutdown();
    }

    public CndConsolidadaResponse findByCnpj(String cnpj) {
        return refreshAheadCache.get(CACHE_NAME, cnpj, SOFT_TTL, () -> loadInParallel(cnpj));
    }

    private CndConsolidadaResponse loadInParallel(String cnpj) {
        CompletableFuture<CndTrabalhista> tstFuture =
                CompletableFuture.supplyAsync(() -> measure(tst.providerName(),
                        () -> tst.fetch(cnpj)), parallelExecutor)
                        .exceptionally(ex -> trabalhistaDegradada(ex));

        CompletableFuture<CndFgts> fgtsFuture =
                CompletableFuture.supplyAsync(() -> measure(fgts.providerName(),
                        () -> fgts.fetch(cnpj)), parallelExecutor)
                        .exceptionally(ex -> fgtsDegradado(ex));

        CompletableFuture<CndFederal> federalFuture =
                CompletableFuture.supplyAsync(() -> measure(federal.providerName(),
                        () -> federal.fetch(cnpj)), parallelExecutor)
                        .exceptionally(ex -> federalDegradada(ex));

        CompletableFuture.allOf(tstFuture, fgtsFuture, federalFuture).join();

        CndTrabalhista trabalhista = tstFuture.join();
        CndFgts fgtsResult = fgtsFuture.join();
        CndFederal federalResult = federalFuture.join();

        int resolvidas = countResolved(trabalhista, fgtsResult, federalResult);
        boolean degradado = resolvidas < 3;

        if (resolvidas == 0) {
            throw new ResourceUnavailableException(DOMAIN,
                    "Todos os provedores de CND indisponíveis simultaneamente — sem dados para consolidar.");
        }

        return new CndConsolidadaResponse(cnpj, trabalhista, fgtsResult, federalResult, resolvidas, degradado);
    }

    private CndTrabalhista trabalhistaDegradada(Throwable ex) {
        log.warn("[{}] TST degradado — emitindo sub-record INDISPONIVEL. cause={}", DOMAIN, ex.toString());
        return new CndTrabalhista("INDISPONIVEL", null, null, null, null, fallbackOnFailure("TST", ex));
    }

    private CndFgts fgtsDegradado(Throwable ex) {
        log.warn("[{}] FGTS/Caixa degradado — emitindo sub-record INDISPONIVEL. cause={}", DOMAIN, ex.toString());
        return new CndFgts("INDISPONIVEL", null, null, null, null, fallbackOnFailure("FGTS", ex));
    }

    private CndFederal federalDegradada(Throwable ex) {
        log.warn("[{}] PGFN/Receita degradado — emitindo sub-record INDISPONIVEL. cause={}", DOMAIN, ex.toString());
        return new CndFederal("INDISPONIVEL", null, null, null, null, fallbackOnFailure("Federal", ex));
    }

    private static String fallbackOnFailure(String label, Throwable ex) {
        Throwable root = ex.getCause() != null ? ex.getCause() : ex;
        return label + " indisponível — " + root.getClass().getSimpleName()
                + (root.getMessage() != null ? ": " + root.getMessage() : "");
    }

    private static int countResolved(CndTrabalhista t, CndFgts f, CndFederal fed) {
        int n = 0;
        if (t != null && !"INDISPONIVEL".equals(t.status())) n++;
        if (f != null && !"INDISPONIVEL".equals(f.status())) n++;
        if (fed != null && !"INDISPONIVEL".equals(fed.status())) n++;
        return n;
    }

    private <T> T measure(String providerName, java.util.function.Supplier<T> supplier) {
        String providerTag = providerName.toLowerCase(Locale.ROOT);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T value = supplier.get();
            recordOutcome(providerTag, "success", sample);
            return value;
        } catch (RuntimeException ex) {
            recordOutcome(providerTag, "failure", sample);
            throw ex;
        }
    }

    private void recordOutcome(String providerTag, String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", providerTag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", providerTag,
                "outcome", outcome).increment();
    }
}
