package br.com.cernebr.gateway_nacional.cadastral.cnpj.client;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjResponse;

/**
 * Contract for any upstream CNPJ provider integration. Implementations are
 * expected to be wrapped by Resilience4j (Circuit Breaker / TimeLimiter) and
 * to convert provider-specific payloads into the unified {@link CnpjResponse}.
 */
public interface CnpjClientProvider {

    /**
     * Resolves the given CNPJ against the upstream provider.
     *
     * @param cnpj raw 14-digit CNPJ (already validated at the controller boundary)
     * @return unified company payload
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable, returns an empty/error payload,
     *         or the Circuit Breaker is OPEN.
     */
    CnpjResponse fetch(String cnpj);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
