package br.com.cernebr.gateway_nacional.cadastral.cep.service;

import br.com.cernebr.gateway_nacional.cadastral.cep.client.AwesomeApiClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.BrasilApiClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.CepClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.ViaCepClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates the cascade fallback across multiple CEP providers and applies
 * in-memory IBGE enrichment before caching/returning the result.
 * Order: ViaCEP → BrasilAPI → AwesomeAPI.
 *
 * <p>Emits two Micrometer instruments per upstream call:</p>
 * <ul>
 *   <li>{@code gateway.provider.requests} — counter, tags {@code domain},
 *       {@code provider}, {@code outcome} (success/failure).</li>
 *   <li>{@code gateway.provider.latency} — timer, tags {@code domain},
 *       {@code provider}.</li>
 * </ul>
 *
 * <p>Metrics fire on cache miss only — {@code @Cacheable} short-circuits the
 * method body on cache hit, so they accurately reflect upstream traffic.</p>
 */
@Slf4j
@Service
public class CepService {

    private static final String DOMAIN = "cep";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<CepClientProvider> providersInOrder;
    private final IbgeEnrichmentService ibgeEnrichmentService;
    private final MeterRegistry meterRegistry;

    public CepService(ViaCepClient primary,
                      BrasilApiClient secondary,
                      AwesomeApiClient tertiary,
                      IbgeEnrichmentService ibgeEnrichmentService,
                      MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
        this.ibgeEnrichmentService = ibgeEnrichmentService;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "ceps", key = "#cep")
    public CepResponse findByCep(String cep) {
        for (CepClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                CepResponse raw = provider.fetch(cep);
                recordOutcome(provider.providerName(), "success", sample);
                CepResponse enriched = ibgeEnrichmentService.enrich(raw);
                log.info("CEP {} resolved by provider={}", cep, provider.providerName());
                return enriched;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for cep={} ({}). Cascading to next provider.",
                        provider.providerName(), cep, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de CEP falharam após o fallback em cascata.");
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
