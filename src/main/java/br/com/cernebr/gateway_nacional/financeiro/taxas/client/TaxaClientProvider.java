package br.com.cernebr.gateway_nacional.financeiro.taxas.client;

import br.com.cernebr.gateway_nacional.financeiro.taxas.dto.TaxaResponse;

/**
 * Contract for any rate provider integration. Implementations are wrapped by
 * Resilience4j (Circuit Breaker / TimeLimiter) and convert provider-specific
 * payloads into the unified {@link TaxaResponse}.
 */
public interface TaxaClientProvider {

    /**
     * Resolves the latest published value of the given financial rate.
     *
     * @param sigla rate identifier — {@code "cdi"}, {@code "selic"} or
     *              {@code "ipca"}, case-insensitive (already validated at
     *              the controller boundary).
     * @return unified rate payload with canonical uppercase name
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable, returns empty/error payload,
     *         the Circuit Breaker is OPEN, or the requested rate is not
     *         supported by this particular provider.
     */
    TaxaResponse fetch(String sigla);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
