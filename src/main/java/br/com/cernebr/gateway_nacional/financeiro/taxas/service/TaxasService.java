package br.com.cernebr.gateway_nacional.financeiro.taxas.service;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.taxas.client.BcbSgsTaxasClient;
import br.com.cernebr.gateway_nacional.financeiro.taxas.client.BrasilApiTaxasClient;
import br.com.cernebr.gateway_nacional.financeiro.taxas.client.HgBrasilTaxasClient;
import br.com.cernebr.gateway_nacional.financeiro.taxas.client.TaxaClientProvider;
import br.com.cernebr.gateway_nacional.financeiro.taxas.dto.TaxaResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates the cascade fallback across rate providers.
 * Order: BrasilAPI → BCB SGS → HG Brasil. Cache key normalizes the sigla to
 * uppercase so {@code "cdi"}, {@code "Cdi"} and {@code "CDI"} share the same
 * Redis entry.
 */
@Slf4j
@Service
public class TaxasService {

    private static final String DOMAIN = "taxas";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<TaxaClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public TaxasService(BrasilApiTaxasClient primary,
                        BcbSgsTaxasClient secondary,
                        HgBrasilTaxasClient tertiary,
                        MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "taxas", key = "#sigla.toUpperCase()")
    public TaxaResponse findBySigla(String sigla) {
        for (TaxaClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                TaxaResponse response = provider.fetch(sigla);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("Rate {} resolved by provider={}", sigla, provider.providerName());
                return response;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for sigla={} ({}). Cascading to next provider.",
                        provider.providerName(), sigla, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de taxas falharam após o fallback em cascata.");
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
