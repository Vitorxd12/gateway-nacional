package br.com.cernebr.gateway_nacional.saude.service;

import br.com.cernebr.gateway_nacional.saude.client.SisabWebClient;
import br.com.cernebr.gateway_nacional.saude.dto.ProducaoSisabResponse;
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
 * Orchestrates SISAB validation report retrieval. Cache TTL of 15 days
 * matches the SISAB publication cycle. Aggressive caching is critical here:
 * each call drives a JSF form submission against gov.br, which is brittle
 * and slow — re-running the same query inside the publication window is
 * pure waste.
 *
 * <p><b>ATENÇÃO: Não migrar para
 * {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor} nem
 * {@link br.com.cernebr.gateway_nacional.config.RefreshAheadCache}.</b>
 * Provider de alto custo computacional (submissão JSF gov.br + Selenium
 * sidecar). Single-provider, então hedge não se aplica de saída; e mesmo
 * uma futura migração para multi-provider precisa ficar em cascata para
 * não disparar múltiplas sessões pesadas. RAC dispararia o mesmo trabalho
 * pesado em background. Mantém {@code @Cacheable} puro.</p>
 */
@Slf4j
@Service
public class SisabService {

    private static final String DOMAIN = "saude";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final SisabWebClient client;
    private final MeterRegistry meterRegistry;

    public SisabService(SisabWebClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "saude", key = "'sisab-' + #ibge + '-' + #competencia")
    public List<ProducaoSisabResponse> findProducao(String ibge, String competencia) {
        YearMonth ym = parseCompetencia(competencia);
        String ibge6 = canonicalIbge(ibge);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<ProducaoSisabResponse> rows = client.fetchProducao(ibge6, ym.getYear(), ym.getMonthValue());
            recordOutcome(client.providerName(), "success", sample);
            return rows;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("SISAB provider failed for IBGE={} competencia={}: {}", ibge6, competencia, ex.getMessage());
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
