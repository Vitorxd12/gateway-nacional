package br.com.cernebr.gateway_nacional.rastreio.service;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.rastreio.client.BrasilApiRastreioClient;
import br.com.cernebr.gateway_nacional.rastreio.client.CorreiosOficialClient;
import br.com.cernebr.gateway_nacional.rastreio.client.LinkAndTrackClient;
import br.com.cernebr.gateway_nacional.rastreio.client.RastreioClientProvider;
import br.com.cernebr.gateway_nacional.rastreio.dto.RastreioResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates the cascade fallback across tracking providers.
 * Order: Link&amp;Track → BrasilAPI → Correios Oficial.
 *
 * <p>Cache key normalizes the tracking code to uppercase via SpEL —
 * {@code "lb123456789br"} and {@code "LB123456789BR"} share the same
 * Redis entry. TTL is short (1h) because tracking events are time-sensitive
 * and a stale read can mislead the end customer.</p>
 */
@Slf4j
@Service
public class RastreioService {

    private static final String DOMAIN = "rastreio";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<RastreioClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public RastreioService(LinkAndTrackClient primary,
                           BrasilApiRastreioClient secondary,
                           CorreiosOficialClient tertiary,
                           MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "rastreios", key = "#codigo.toUpperCase()")
    public RastreioResponse findByCodigo(String codigo) {
        for (RastreioClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                RastreioResponse response = provider.fetch(codigo);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("Tracking {} resolved by provider={}", codigo, provider.providerName());
                return response;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for codigo={} ({}). Cascading to next provider.",
                        provider.providerName(), codigo, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de rastreio falharam após o fallback em cascata.");
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
