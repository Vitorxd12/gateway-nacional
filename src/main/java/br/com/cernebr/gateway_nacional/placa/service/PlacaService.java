package br.com.cernebr.gateway_nacional.placa.service;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.placa.client.KeplacaClient;
import br.com.cernebr.gateway_nacional.placa.client.PlacaClientProvider;
import br.com.cernebr.gateway_nacional.placa.client.PlacaFipeScraperClient;
import br.com.cernebr.gateway_nacional.placa.client.WdApiPlacaClient;
import br.com.cernebr.gateway_nacional.placa.dto.PlacaResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates the cascade fallback for vehicle lookup by license plate.
 * Order: <b>WDApi → Keplaca → PlacaFipe scraper</b>.
 *
 * <p>The third provider is the safety net that turns the gateway into a
 * "100% gratuito" deploy: when both paid providers are unconfigured (token
 * placeholders) or out, the PlacaFipe scraper still resolves the placa
 * <i>and</i> enriches the response with the {@code codigoFipe} association
 * neither paid provider exposes. Avaliação ({@code /api/v1/avaliacao/placa})
 * benefits directly — no manual codigoFipe needed when the placa is
 * resolved by this provider.</p>
 *
 * <p>Cache key normalizes the placa to uppercase via SpEL — combined with
 * the controller-side hyphen stripping, this guarantees a single Redis entry
 * per real-world plate. TTL is aggressive (365 days) because the placa-to-
 * vehicle binding is essentially permanent for the lifetime of the
 * registration.</p>
 */
@Slf4j
@Service
public class PlacaService {

    private static final String DOMAIN = "placa";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<PlacaClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public PlacaService(WdApiPlacaClient primary,
                        KeplacaClient secondary,
                        PlacaFipeScraperClient tertiary,
                        MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "placas", key = "#placa.toUpperCase()")
    public PlacaResponse findByPlaca(String placa) {
        for (PlacaClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                PlacaResponse response = provider.fetchByPlaca(placa);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("Placa {} resolved by provider={}", placa, provider.providerName());
                return response;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for placa={} ({}). Cascading to next provider.",
                        provider.providerName(), placa, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de placa falharam após o fallback em cascata.");
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
