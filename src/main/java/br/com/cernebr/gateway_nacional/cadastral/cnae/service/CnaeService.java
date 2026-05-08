package br.com.cernebr.gateway_nacional.cadastral.cnae.service;

import br.com.cernebr.gateway_nacional.cadastral.cnae.client.CnaeClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.cnae.client.IbgeCnaeClient;
import br.com.cernebr.gateway_nacional.cadastral.cnae.client.LocalCnaeClient;
import br.com.cernebr.gateway_nacional.cadastral.cnae.dto.CnaeResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrates the cascade fallback for CNAE (Classificação Nacional de
 * Atividades Econômicas).
 *
 * <p>Order: <b>IBGE → Local snapshot</b>. IBGE is the canonical authority
 * (publishes the table) and stays primary; the local bake-in snapshot is
 * a defensive layer for the (rare) IBGE outage.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>Returns {@link CnaeResponse} when any provider has the code.</li>
 *   <li>Throws {@link ResourceNotFoundException} (404) when every reachable
 *       provider answered "not found" consistently — definitive answer
 *       that gets cached for {@code ncmCache} TTL (re-querying upstream
 *       for codes that simply don't exist would be wasteful).</li>
 *   <li>Throws {@link ResourceUnavailableException} (503) only when every
 *       provider was unreachable AND the local fallback could not be
 *       consulted either — practically impossible since the local
 *       snapshot is in-process memory.</li>
 * </ul>
 */
@Slf4j
@Service
public class CnaeService {

    private static final String DOMAIN = "cnae";
    private static final String AGGREGATE_PROVIDER = "all-providers";
    static final String CNAE_CACHE = "cnae";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<CnaeClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public CnaeService(IbgeCnaeClient primary,
                       LocalCnaeClient secondary,
                       MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = CNAE_CACHE, key = "'codigo-' + #codigo")
    public CnaeResponse findByCodigo(String codigo) {
        boolean anyProviderUnavailable = false;
        Throwable lastFailure = null;

        for (CnaeClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                Optional<CnaeResponse> result = provider.findByCodigo(codigo);
                if (result.isPresent()) {
                    recordOutcome(provider.providerName(), "success", sample);
                    log.info("CNAE {} resolved by provider={}", codigo, provider.providerName());
                    return result.get();
                }
                recordOutcome(provider.providerName(), "not-found", sample);
                log.debug("CNAE {} not found in provider={}", codigo, provider.providerName());
            } catch (ResourceUnavailableException ex) {
                anyProviderUnavailable = true;
                lastFailure = ex;
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for CNAE {} ({}). Cascading.",
                        provider.providerName(), codigo, ex.getMessage());
            }
        }

        if (!anyProviderUnavailable) {
            throw new ResourceNotFoundException("CNAE",
                    "CNAE " + codigo + " não consta na tabela CONCLA "
                            + "(verificado em todos os provedores).");
        }

        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de CNAE falharam após o fallback em cascata.", lastFailure);
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
