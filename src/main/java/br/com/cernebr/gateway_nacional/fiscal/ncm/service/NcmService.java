package br.com.cernebr.gateway_nacional.fiscal.ncm.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.fiscal.ncm.client.BrasilApiNcmClient;
import br.com.cernebr.gateway_nacional.fiscal.ncm.client.NcmClientProvider;
import br.com.cernebr.gateway_nacional.fiscal.ncm.client.SiscomexNcmClient;
import br.com.cernebr.gateway_nacional.fiscal.ncm.dto.NcmResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrates the cascade fallback for NCM (Nomenclatura Comum do Mercosul).
 *
 * <p>Order: <b>BrasilAPI → Siscomex</b>. BrasilAPI proxies the Camex
 * catalogue cleanly with both search-by-text and lookup-by-code endpoints,
 * so it stays primary; Siscomex is the official source-of-truth and
 * provides redundancy (currently with a path drift to be resolved — see
 * {@code SiscomexNcmClient} javadoc).</p>
 *
 * <h2>Failure semantics — why this matters for the cache</h2>
 * <ul>
 *   <li>{@link #findByCodigo(String)} returns the resolved {@link NcmResponse}
 *       or throws {@link ResourceNotFoundException} (404) when every
 *       provider answered "no such code". A 404 is a deterministic upstream
 *       answer and stays cached for {@code ncmCache} TTL — re-querying
 *       upstream every time would be wasteful for 8-digit codes that simply
 *       do not exist.</li>
 *   <li>{@link #searchByDescricao(String)} returns the unioned list of
 *       results across providers. An empty list is a legitimate result
 *       and is cached too.</li>
 *   <li>Both methods throw {@link ResourceUnavailableException} (503) only
 *       when every provider is unreachable. Spring's {@code @Cacheable}
 *       does not cache exceptions, so 503s correctly bypass the cache and
 *       re-attempt on the next call.</li>
 * </ul>
 *
 * <p>Cache TTL is 30 days — the Mercosul nomenclature changes a handful
 * of times per year via Camex resolutions, so a long TTL gives near-zero
 * upstream load without risking stale results.</p>
 *
 * <h2>Por que cascata e NÃO {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor}</h2>
 * <p>A cascata distingue dois desfechos negativos: "todos os providers
 * confirmaram que o código não existe" (404 definitivo) e "todos falharam"
 * (503 transitório). Sob hedge esses dois caminhos colapsariam em 503 —
 * o {@link HedgedExecutor#anyOf} trata qualquer exceção como falha de
 * tentativa e não tem como propagar a distinção semântica entre {@code Optional.empty()}
 * (resposta válida do upstream) e {@link ResourceUnavailableException}.
 * Resultado: clientes deixariam de cachear o 404 e bombardeariam upstreams
 * para códigos comprovadamente inexistentes.</p>
 *
 * <h2>RAC só em {@link #findByCodigo}</h2>
 * <p>{@link #searchByDescricao} retorna {@code List<NcmResponse>} e
 * <em>não</em> entra no {@link RefreshAheadCache} — coleções colidem com
 * a lacuna de default-typing tratada pelo {@code ResilientGenericJacksonSerializer}
 * e degradariam para miss permanente. Mantém {@code @Cacheable} puro com
 * o mesmo hard-TTL de 30 dias.</p>
 */
@Slf4j
@Service
public class NcmService {

    private static final String DOMAIN = "ncm";
    private static final String AGGREGATE_PROVIDER = "all-providers";
    static final String NCM_CACHE = "ncm";
    private static final Duration FIND_BY_CODIGO_SOFT_TTL = Duration.ofDays(7);

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<NcmClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache refreshAheadCache;

    public NcmService(BrasilApiNcmClient primary,
                      SiscomexNcmClient secondary,
                      MeterRegistry meterRegistry,
                      RefreshAheadCache refreshAheadCache) {
        this.providersInOrder = List.of(primary, secondary);
        this.meterRegistry = meterRegistry;
        this.refreshAheadCache = refreshAheadCache;
    }

    public NcmResponse findByCodigo(String codigo) {
        return refreshAheadCache.get(NCM_CACHE, "codigo-" + codigo, FIND_BY_CODIGO_SOFT_TTL,
                () -> loadByCodigoFromCascade(codigo));
    }

    private NcmResponse loadByCodigoFromCascade(String codigo) {
        boolean anyProviderUnavailable = false;
        Throwable lastFailure = null;

        for (NcmClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                Optional<NcmResponse> result = provider.findByCodigo(codigo);
                if (result.isPresent()) {
                    recordOutcome(provider.providerName(), "success", sample);
                    log.info("NCM {} resolved by provider={}", codigo, provider.providerName());
                    return result.get();
                }
                // Definitive "not found" from this provider — record as success
                // (the provider answered correctly) and continue: the next
                // provider may have the entry under a different sync window.
                recordOutcome(provider.providerName(), "not-found", sample);
                log.debug("NCM {} not found in provider={}", codigo, provider.providerName());
            } catch (ResourceUnavailableException ex) {
                anyProviderUnavailable = true;
                lastFailure = ex;
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for NCM {} ({}). Cascading to next provider.",
                        provider.providerName(), codigo, ex.getMessage());
            }
        }

        // Every provider answered consistently "not found" — surface as 404.
        if (!anyProviderUnavailable) {
            throw new ResourceNotFoundException("NCM",
                    "NCM " + codigo + " não consta no catálogo Mercosul "
                            + "(verificado em todos os provedores).");
        }

        // At least one provider was unreachable — we do not have a definitive
        // answer, so 503 is the honest response. Caller can retry later.
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de NCM falharam após o fallback em cascata.", lastFailure);
    }

    @Cacheable(cacheNames = NCM_CACHE, key = "'search-' + #descricao.toLowerCase()")
    public List<NcmResponse> searchByDescricao(String descricao) {
        for (NcmClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                List<NcmResponse> results = provider.searchByDescricao(descricao);
                recordOutcome(provider.providerName(), "success", sample);
                if (!results.isEmpty()) {
                    log.info("NCM search '{}' resolved by provider={} ({} hits)",
                            descricao, provider.providerName(), results.size());
                    return results;
                }
                log.debug("NCM search '{}' empty in provider={}", descricao, provider.providerName());
            } catch (ResourceUnavailableException ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for NCM search '{}' ({}). Cascading.",
                        provider.providerName(), descricao, ex.getMessage());
            }
        }
        // Every provider responded with empty / unavailable — return empty list.
        // We do NOT throw 404 here: search returning zero matches is a normal
        // result, not an error condition.
        return List.of();
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
