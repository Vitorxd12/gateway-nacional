package br.com.cernebr.gateway_nacional.bancos.service;

import br.com.cernebr.gateway_nacional.bancos.client.BancoClientProvider;
import br.com.cernebr.gateway_nacional.bancos.client.BrasilApiBancoClient;
import br.com.cernebr.gateway_nacional.bancos.client.LocalBacenBancoClient;
import br.com.cernebr.gateway_nacional.bancos.dto.BancoResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Orchestrates the cascade fallback for the bank catalogue.
 * Order: BrasilAPI → in-memory BACEN dump.
 *
 * <p>Cache is aggressive — bank registrations are highly stable. The full
 * list shares cache name with single-bank lookups but uses the literal key
 * {@code "all"} to avoid collision with any 3-digit COMPE code.</p>
 */
@Slf4j
@Service
public class BancoService {

    private static final String DOMAIN = "bancos";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<BancoClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public BancoService(BrasilApiBancoClient primary,
                        LocalBacenBancoClient secondary,
                        MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "bancos", key = "'all'")
    public List<BancoResponse> findAll() {
        return cascade(BancoClientProvider::fetchAll, "fetchAll", "n/a");
    }

    @Cacheable(cacheNames = "bancos", key = "#codigo")
    public BancoResponse findByCodigo(String codigo) {
        return cascade(provider -> provider.fetchByCodigo(codigo), "fetchByCodigo", codigo);
    }

    /**
     * Generic cascade runner — the only place where provider iteration,
     * exception handling and metric emission live. Operation type and key
     * are passed in so logs stay informative.
     */
    private <T> T cascade(Function<BancoClientProvider, T> call, String operation, String key) {
        for (BancoClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                T result = call.apply(provider);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("Bancos {}({}) resolved by provider={}", operation, key, provider.providerName());
                return result;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for {}({}) ({}). Cascading to next provider.",
                        provider.providerName(), operation, key, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de bancos falharam após o fallback em cascata.");
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
