package br.com.cernebr.gateway_nacional.saude.service;

import br.com.cernebr.gateway_nacional.saude.client.EGestorWebClient;
import br.com.cernebr.gateway_nacional.saude.dto.EquipeEGestorResponse;
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
 * Orchestrates e-Gestor APS team retrieval. Same single-provider posture as
 * {@link FnsService} for the same reason — the e-Gestor APS portal is the
 * authoritative source. The orchestrator skeleton is preserved so a future
 * mirror provider can drop in without touching the controller or callers.
 *
 * <p>Cache TTL of 15 days matches the federal payment cycle; the e-Gestor
 * publication is monthly and rarely revised inside that window.</p>
 */
@Slf4j
@Service
public class EGestorService {

    private static final String DOMAIN = "saude";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final EGestorWebClient client;
    private final MeterRegistry meterRegistry;

    public EGestorService(EGestorWebClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "saude", key = "'egestor-' + #ibge + '-' + #competencia")
    public List<EquipeEGestorResponse> findEquipes(String ibge, String competencia) {
        YearMonth ym = parseCompetencia(competencia);
        String ibge6 = canonicalIbge(ibge);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<EquipeEGestorResponse> equipes = client.fetchEquipes(ibge6, ym.getYear(), ym.getMonthValue());
            recordOutcome(client.providerName(), "success", sample);
            return equipes;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("e-Gestor provider failed for IBGE={} competencia={}: {}", ibge6, competencia, ex.getMessage());
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
