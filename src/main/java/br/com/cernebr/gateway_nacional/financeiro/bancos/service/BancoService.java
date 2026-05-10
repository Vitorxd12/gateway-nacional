package br.com.cernebr.gateway_nacional.financeiro.bancos.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.financeiro.bancos.client.BancoClientProvider;
import br.com.cernebr.gateway_nacional.financeiro.bancos.client.BrasilApiBancoClient;
import br.com.cernebr.gateway_nacional.financeiro.bancos.client.LocalBacenBancoClient;
import br.com.cernebr.gateway_nacional.financeiro.bancos.dto.BancoResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 *
 * <h2>Por que cascata e NÃO {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor}</h2>
 * <p>O segundo provider é um dump in-memory que sob hedge venceria todas as
 * corridas, e o BrasilAPI nunca seria consultado para refrescar o catálogo.
 * A cascata sequencial preserva a intenção: BrasilAPI primeiro; o local só
 * é o paraquedas quando o REST cair.</p>
 *
 * <h2>RAC só em {@link #findByCodigo}</h2>
 * <p>{@link #findAll} retorna {@code List<BancoResponse>} e <em>não</em> usa
 * {@link RefreshAheadCache} — wrapping de coleções colidiria com a lacuna
 * de default-typing tratada pelo {@code ResilientGenericJacksonSerializer}
 * e degradaria a chave {@code "all"} para miss permanente. Mantém
 * {@code @Cacheable} puro com hard-TTL de 30 dias, idêntico ao
 * comportamento anterior.</p>
 */
@Slf4j
@Service
public class BancoService {

    private static final String DOMAIN = "bancos";
    private static final String AGGREGATE_PROVIDER = "all-providers";
    private static final String CACHE_NAME = "bancos";
    private static final Duration FIND_BY_CODIGO_SOFT_TTL = Duration.ofDays(7);

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<BancoClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache refreshAheadCache;

    public BancoService(BrasilApiBancoClient primary,
                        LocalBacenBancoClient secondary,
                        MeterRegistry meterRegistry,
                        RefreshAheadCache refreshAheadCache) {
        this.providersInOrder = List.of(primary, secondary);
        this.meterRegistry = meterRegistry;
        this.refreshAheadCache = refreshAheadCache;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "'all'")
    public List<BancoResponse> findAll() {
        return cascade(BancoClientProvider::fetchAll, "fetchAll", "n/a");
    }

    public BancoResponse findByCodigo(String codigo) {
        return refreshAheadCache.get(CACHE_NAME, codigo, FIND_BY_CODIGO_SOFT_TTL,
                () -> cascade(provider -> provider.fetchByCodigo(codigo), "fetchByCodigo", codigo));
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
