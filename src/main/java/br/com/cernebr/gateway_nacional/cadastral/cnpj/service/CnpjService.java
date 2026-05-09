package br.com.cernebr.gateway_nacional.cadastral.cnpj.service;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.BrasilApiClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.MinhaReceitaClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.ReceitaWsClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates the cascade fallback across multiple CNPJ providers.
 * Order: BrasilAPI → ReceitaWS → MinhaReceita. Metrics shape mirrors
 * {@code CepService} so dashboards can stay domain-agnostic.
 */
@Slf4j
@Service
public class CnpjService {

    private static final String DOMAIN = "cnpj";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<CnpjClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public CnpjService(BrasilApiClient primary,
                       ReceitaWsClient secondary,
                       MinhaReceitaClient tertiary,
                       MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "cnpjs", key = "#cnpj")
    public CnpjResponse findByCnpj(String cnpj) {
        for (CnpjClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                CnpjResponse response = provider.fetch(cnpj);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("CNPJ {} resolved by provider={}", cnpj, provider.providerName());
                return response;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for cnpj={} ({}). Cascading to next provider.",
                        provider.providerName(), cnpj, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de CNPJ falharam após o fallback em cascata.");
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
