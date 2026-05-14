package br.com.cernebr.gateway_nacional.veicular.historico.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.veicular.historico.client.HistoricoEvidencia;
import br.com.cernebr.gateway_nacional.veicular.historico.client.HistoricoScraperClient;
import br.com.cernebr.gateway_nacional.veicular.historico.dto.HistoricoVeicularDTO;
import br.com.cernebr.gateway_nacional.veicular.historico.dto.RiscoConsolidado;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Free-tier vehicle history orchestrator — fans out to every
 * {@link HistoricoScraperClient} in parallel on virtual threads, applies a
 * hard wall-time cap (12s default) so a single hanging upstream cannot
 * stretch the response, and consolidates the union of evidence into a
 * single {@link HistoricoVeicularDTO}.
 *
 * <p><b>Fail-soft posture:</b> any scraper that throws, times out, or has
 * its Circuit Breaker open is dropped from {@code fontesConsultadas} —
 * the response stays 200 and reflects the union of the survivors. The
 * orchestrator only escalates to 503 when <i>every</i> source failed
 * AND no usable evidence was collected.</p>
 *
 * <p><b>Cache:</b> {@link RefreshAheadCache} with soft TTL 6h / hard TTL
 * 24h on the {@code historicoVeicular} cache. Soft-TTL hits serve the
 * cached DTO while a background virtual thread refreshes — this dampens
 * traffic on the rate-limited free-tier sources (Cloudflare aggressively
 * throttles repeat hits to the same placa from the same IP).</p>
 */
@Slf4j
@Service
public class HistoricoService {

    private static final String DOMAIN = "historicoVeicular";
    private static final String CACHE_NAME = "historicoVeicular";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    /**
     * Hard wall-time across the whole fan-out. Sits inside the per-CB
     * timeoutDuration (each CB has 8-10s) so a single laggard still gets
     * cancelled before it blocks the join. 12s is the equilibrium between
     * giving FlareSolverr enough time to solve a fresh Cloudflare challenge
     * and not pinning a HTTP worker.
     */
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(12);

    private static final Duration CACHE_SOFT_TTL = Duration.ofHours(6);

    private final List<HistoricoScraperClient> scrapers;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache cache;

    public HistoricoService(List<HistoricoScraperClient> scrapers,
                            MeterRegistry meterRegistry,
                            RefreshAheadCache cache) {
        // Defensive copy so test wiring can pass a mutable list without
        // poisoning the production bean.
        this.scrapers = List.copyOf(scrapers);
        this.meterRegistry = meterRegistry;
        this.cache = cache;
    }

    /**
     * Resolves the historico view for the given placa. The placa is already
     * normalised at the controller boundary (uppercase, no hyphen).
     *
     * @return consolidated DTO. Empty {@code fontesConsultadas} is allowed —
     *         signals that every upstream failed and the caller should
     *         treat the response as "consulta inconclusiva" (still a 200,
     *         per the fail-soft contract).
     */
    public HistoricoVeicularDTO consultar(String placa) {
        return cache.get(CACHE_NAME, placa, CACHE_SOFT_TTL, () -> doConsultar(placa));
    }

    private HistoricoVeicularDTO doConsultar(String placa) {
        // newVirtualThreadPerTaskExecutor is the right fit: every task is
        // an I/O-bound scraper hit, so spawning N threads is essentially
        // free on the JVM platform thread pool. try-with-resources here
        // is *not* used because we set a hard cap on join with awaitTermination
        // — see comment below.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<HistoricoEvidencia>> futures = new ArrayList<>(scrapers.size());
            for (HistoricoScraperClient scraper : scrapers) {
                futures.add(CompletableFuture.supplyAsync(() -> safeFetch(scraper, placa), executor));
            }

            // Wait for the whole fan-out to settle, but enforce the hard
            // wall-time. allOf().get(timeout) cancels stragglers via
            // CompletableFuture#cancel — virtual threads honor this and
            // the underlying socket reads are interrupted at the next yield.
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(CompletableFuture[]::new));
            try {
                all.get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                log.warn("Hard timeout {}ms reached for placa={}; cancelling stragglers.",
                        HARD_TIMEOUT.toMillis(), placa);
                for (CompletableFuture<HistoricoEvidencia> f : futures) {
                    if (!f.isDone()) f.cancel(true);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                // allOf() rethrows the underlying ExecutionException only
                // when every future failed AND that failure escaped
                // safeFetch — which is impossible because safeFetch never
                // re-throws. So this branch is purely defensive.
                log.warn("allOf() escaped exception unexpectedly: {}", ex.toString());
            }

            List<HistoricoEvidencia> evidencias = new ArrayList<>(futures.size());
            for (CompletableFuture<HistoricoEvidencia> f : futures) {
                try {
                    if (f.isDone() && !f.isCancelled() && !f.isCompletedExceptionally()) {
                        HistoricoEvidencia e = f.get();
                        if (e != null) evidencias.add(e);
                    }
                } catch (Exception ignored) {
                    // safeFetch absorbs all exceptions and surfaces them as
                    // null returns; reaching here only happens for cancelled
                    // stragglers, which are intentionally dropped.
                }
            }

            return consolidar(placa, evidencias);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Per-scraper guard: surfaces a successful evidence on the happy path
     * and {@code null} on any failure (timeout, CB open, parser blow-up).
     * The orchestrator interprets {@code null} as "drop this source" — never
     * a thrown exception, because a thrown exception propagates through
     * CompletableFuture and would force callers to handle ExecutionException
     * defensively. Returning a sentinel keeps the join site clean.
     */
    private HistoricoEvidencia safeFetch(HistoricoScraperClient scraper, String placa) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            HistoricoEvidencia ev = scraper.consultar(placa);
            recordOutcome(scraper.providerName(), "success", sample);
            return ev;
        } catch (Exception ex) {
            recordOutcome(scraper.providerName(), "failure", sample);
            log.warn("Historico scraper {} failed for placa={}: {}",
                    scraper.providerName(), placa, ex.getMessage());
            return null;
        }
    }

    /**
     * Reduces N evidences into the final DTO. Booleans flip true on any
     * single hit; {@code detalhesLeilao} concatenates the per-source
     * one-liners separated by {@code " | "} — readable in a single line
     * inside a CLI/log and parseable on the client side.
     */
    private HistoricoVeicularDTO consolidar(String placa, List<HistoricoEvidencia> evidencias) {
        boolean indicioLeilao = false;
        boolean indicioSinistro = false;
        StringBuilder detalhes = new StringBuilder();
        List<String> fontes = new ArrayList<>(evidencias.size());

        for (HistoricoEvidencia e : evidencias) {
            fontes.add(e.fonte());
            if (e.indicioLeilao()) indicioLeilao = true;
            if (e.indicioSinistro()) indicioSinistro = true;
            if (e.detalhe() != null && !e.detalhe().isBlank()) {
                if (!detalhes.isEmpty()) detalhes.append(" | ");
                detalhes.append('[').append(e.fonte()).append("] ").append(e.detalhe());
            }
        }

        String detalheStr = detalhes.isEmpty() ? null : detalhes.toString();
        RiscoConsolidado risco = RiscoConsolidado.from(indicioLeilao, indicioSinistro);

        log.info("Historico consolidado placa={} fontes={} indicioLeilao={} indicioSinistro={} risco={}",
                placa, fontes, indicioLeilao, indicioSinistro, risco);

        return new HistoricoVeicularDTO(
                placa,
                indicioLeilao,
                indicioSinistro,
                detalheStr,
                risco,
                List.copyOf(fontes)
        );
    }

    private void recordOutcome(String providerName, String outcome, Timer.Sample sample) {
        String providerTag = providerName.toLowerCase(Locale.ROOT);
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
