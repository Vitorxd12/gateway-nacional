package br.com.cernebr.gateway_nacional.cadastral.simples.service;

import br.com.cernebr.gateway_nacional.cadastral.simples.client.SimplesNacionalClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.simples.client.SimplesNacionalFallbackClient;
import br.com.cernebr.gateway_nacional.cadastral.simples.client.SimplesNacionalReceitaClient;
import br.com.cernebr.gateway_nacional.cadastral.simples.dto.SimplesNacionalResponse;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Orquestrador do enquadramento Simples Nacional.
 *
 * <p><b>Estratégia em CASCATA (não hedge):</b> diferente do CNPJ, aqui o
 * provedor primário (scraper oficial) carrega informação <em>exclusiva</em> —
 * histórico de exclusões — que o fallback REST não devolve. Disparar os dois
 * em paralelo faria o fallback REST vencer o hedge sistematicamente
 * (latência ~600ms vs scraper ~1.8s) e descartaríamos a informação rica.
 * A cascata garante: tenta o oficial, e só recua se ele realmente falhar.</p>
 */
@Slf4j
@Service
public class SimplesNacionalService {

    private static final String DOMAIN = "simples";
    private static final String CACHE_NAME = "simples";
    private static final Duration SOFT_TTL = Duration.ofHours(12);
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<SimplesNacionalClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache refreshAheadCache;

    public SimplesNacionalService(SimplesNacionalReceitaClient primary,
                                  SimplesNacionalFallbackClient fallback,
                                  MeterRegistry meterRegistry,
                                  RefreshAheadCache refreshAheadCache) {
        this.providersInOrder = List.of(primary, fallback);
        this.meterRegistry = meterRegistry;
        this.refreshAheadCache = refreshAheadCache;
    }

    public SimplesNacionalResponse findByCnpj(String cnpj) {
        return refreshAheadCache.get(CACHE_NAME, cnpj, SOFT_TTL, () -> loadFromProviders(cnpj));
    }

    private SimplesNacionalResponse loadFromProviders(String cnpj) {
        Throwable lastFailure = null;
        for (SimplesNacionalClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            String tag = provider.providerName().toLowerCase(Locale.ROOT);
            try {
                SimplesNacionalResponse response = provider.fetch(cnpj);
                recordOutcome(tag, "success", sample);
                log.info("Simples cnpj={} resolved by provider={}", cnpj, provider.providerName());
                return response;
            } catch (ResourceUnavailableException ex) {
                recordOutcome(tag, "failure", sample);
                lastFailure = ex;
                log.warn("Provider {} failed for Simples cnpj={} ({}). Cascading.",
                        provider.providerName(), cnpj, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(DOMAIN,
                "Todos os provedores de Simples Nacional falharam após cascata.", lastFailure);
    }

    private void recordOutcome(String providerTag, String outcome, Timer.Sample sample) {
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
