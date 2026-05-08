package br.com.cernebr.gateway_nacional.fiscal.ncm.client;

import br.com.cernebr.gateway_nacional.fiscal.ncm.dto.NcmResponse;

import java.util.List;
import java.util.Optional;

/**
 * Contract for any NCM (Nomenclatura Comum do Mercosul) integration.
 * Implementations are wrapped by Resilience4j (Circuit Breaker) and
 * convert provider-specific payloads into the unified {@link NcmResponse}.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>{@link #findByCodigo(String)} returns {@link Optional#empty()} when
 *       the upstream answers definitively that the code does not exist.
 *       The service translates an empty across all providers into a
 *       {@code ResourceNotFoundException} (HTTP 404).</li>
 *   <li>{@link #searchByDescricao(String)} returns an <b>empty list</b> for
 *       no-match (legitimate query result), never {@link Optional#empty()}.
 *       An empty search is a successful outcome, not a 404.</li>
 *   <li>Both methods throw {@code ResourceUnavailableException} when the
 *       upstream itself is unreachable, returns 5xx, or the Circuit
 *       Breaker is OPEN — that triggers cascade fallback in the service.</li>
 * </ul>
 */
public interface NcmClientProvider {

    /**
     * Resolves a single NCM entry by its 8-digit code (with or without
     * separators — the implementation normalises).
     *
     * @return the entry when found; {@link Optional#empty()} when the
     *         upstream answers "no such code" definitively.
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable or the Circuit Breaker is OPEN.
     */
    Optional<NcmResponse> findByCodigo(String codigo);

    /**
     * Full-text search over the official Mercosul descriptions.
     *
     * @return zero-or-more matching entries; empty list when nothing matched.
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable or the Circuit Breaker is OPEN.
     */
    List<NcmResponse> searchByDescricao(String descricao);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
