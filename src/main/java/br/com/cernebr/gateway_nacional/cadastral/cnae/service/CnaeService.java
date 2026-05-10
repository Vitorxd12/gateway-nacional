package br.com.cernebr.gateway_nacional.cadastral.cnae.service;

import br.com.cernebr.gateway_nacional.cadastral.cnae.client.CnaeClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.cnae.client.IbgeCnaeClient;
import br.com.cernebr.gateway_nacional.cadastral.cnae.client.LocalCnaeClient;
import br.com.cernebr.gateway_nacional.cadastral.cnae.dto.CnaeResponse;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 * <h2>Por que cascata e NÃO {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor}</h2>
 * <p>O segundo provider é um snapshot in-memory (latência sub-milissegundo)
 * que sob hedge venceria todas as corridas e o IBGE nunca seria consultado —
 * o cache nunca veria dados frescos. A cascata sequencial preserva a
 * intenção: tentar o IBGE primeiro, cair no local só quando o IBGE falhar
 * de verdade.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>Returns {@link CnaeResponse} when any provider has the code.</li>
 *   <li>Throws {@link ResourceNotFoundException} (404) when every reachable
 *       provider answered "not found" consistently — definitive answer
 *       que entra no RAC normalmente; recomputar para um código que
 *       comprovadamente não existe é desperdício.</li>
 *   <li>Throws {@link ResourceUnavailableException} (503) only when every
 *       provider was unreachable AND the local fallback could not be
 *       consulted either — practically impossible since the local
 *       snapshot is in-process memory.</li>
 * </ul>
 *
 * <h2>RAC sobre {@link #findByCodigo}</h2>
 * <p>Soft-TTL de 7 dias / hard-TTL de 30 dias (configurado em CacheConfig):
 * tabela CONCLA muda raras vezes por ano, então 7 dias de "ociosidade"
 * antes do refresh-ahead garante que chave fria não dispare recargas
 * supérfluas, e a janela soft→hard ainda absorve eventual lentidão do
 * IBGE durante o background refresh.</p>
 */
@Slf4j
@Service
public class CnaeService {

    private static final String DOMAIN = "cnae";
    private static final String AGGREGATE_PROVIDER = "all-providers";
    static final String CNAE_CACHE = "cnae";
    private static final Duration FIND_BY_CODIGO_SOFT_TTL = Duration.ofDays(7);

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<CnaeClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache refreshAheadCache;

    public CnaeService(IbgeCnaeClient primary,
                       LocalCnaeClient secondary,
                       MeterRegistry meterRegistry,
                       RefreshAheadCache refreshAheadCache) {
        this.providersInOrder = List.of(primary, secondary);
        this.meterRegistry = meterRegistry;
        this.refreshAheadCache = refreshAheadCache;
    }

    public CnaeResponse findByCodigo(String codigo) {
        return refreshAheadCache.get(CNAE_CACHE, "codigo-" + codigo, FIND_BY_CODIGO_SOFT_TTL,
                () -> loadByCodigoFromCascade(codigo));
    }

    private CnaeResponse loadByCodigoFromCascade(String codigo) {
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
