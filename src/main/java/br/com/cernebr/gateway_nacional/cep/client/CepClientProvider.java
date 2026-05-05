package br.com.cernebr.gateway_nacional.cep.client;

import br.com.cernebr.gateway_nacional.cep.dto.CepResponse;

/**
 * Contract for any upstream CEP provider integration. Implementations are
 * expected to be wrapped by Resilience4j (Circuit Breaker / TimeLimiter) and
 * to convert provider-specific payloads into the unified {@link CepResponse}.
 */
public interface CepClientProvider {

    /**
     * Resolves the given CEP against the upstream provider.
     *
     * @param cep raw 8-digit CEP (already validated at the controller boundary)
     * @return unified address payload
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable, returns an empty/error payload,
     *         or the Circuit Breaker is OPEN.
     */
    CepResponse fetch(String cep);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
