package br.com.cernebr.gateway_nacional.saude.service;

import br.com.cernebr.gateway_nacional.saude.client.FnsWebClient;
import br.com.cernebr.gateway_nacional.saude.dto.RepasseFnsResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates FNS repasse retrieval. Single-provider for now (the FNS portal
 * is the authoritative source — there is no functional equivalent we can
 * cascade to), but kept on the same orchestrator pattern so that:
 * <ul>
 *   <li>cache hits short-circuit before the brittle gov.br hop is attempted;</li>
 *   <li>metrics emit consistently with every other domain;</li>
 *   <li>adding a future mirror provider is a one-line {@link List} change.</li>
 * </ul>
 *
 * <p>Cache TTL of 15 days mirrors the federal repasse cycle — repasses are
 * consolidated monthly and rarely revised within the publication window.
 * Aggressive caching is intentional: each FNS call is a multi-step session
 * dance, and hammering it triggers anti-bot blocks.</p>
 */
@Slf4j
@Service
public class FnsService {

    private static final String DOMAIN = "saude";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final FnsWebClient client;
    private final MeterRegistry meterRegistry;

    public FnsService(FnsWebClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "saude", key = "'fns-' + #ibge + '-' + #competencia")
    public List<RepasseFnsResponse> findRepasses(String ibge, String competencia) {
        YearMonth ym = parseCompetencia(competencia);
        String ibge6 = canonicalIbge(ibge);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<RepasseFnsResponse> repasses = client.fetchRepasses(ibge6, ym.getYear(), ym.getMonthValue(), competencia);
            recordOutcome(client.providerName(), "success", sample);
            return repasses;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("FNS provider failed for IBGE={} competencia={}: {}", ibge6, competencia, ex.getMessage());
            throw ex;
        }
    }

    private static YearMonth parseCompetencia(String competencia) {
        try {
            return YearMonth.parse(competencia);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Competência inválida: '" + competencia + "'. Esperado formato yyyy-MM.", ex);
        }
    }

    /**
     * Accepts both 6-digit (SUS canonical) and 7-digit IBGE codes. The 7th
     * digit is a verifier and is not used by FNS; we drop it.
     */
    private static String canonicalIbge(String ibge) {
        if (ibge == null) {
            throw new IllegalArgumentException("IBGE obrigatório.");
        }
        return ibge.length() >= 7 ? ibge.substring(0, 6) : ibge;
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
