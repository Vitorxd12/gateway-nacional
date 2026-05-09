package br.com.cernebr.gateway_nacional.operacional.rastreio.client;

import br.com.cernebr.gateway_nacional.operacional.rastreio.dto.RastreioResponse;

/**
 * Contract for any tracking provider integration. Implementations are wrapped
 * by Resilience4j (Circuit Breaker / TimeLimiter) and convert provider-specific
 * payloads into the unified {@link RastreioResponse}.
 */
public interface RastreioClientProvider {

    /**
     * Resolves the tracking history of the given parcel code.
     *
     * @param codigo Correios tracking code in canonical upper-case form
     *               (e.g. {@code "LB123456789BR"}). Already validated by the
     *               controller boundary.
     * @return unified tracking payload with events ordered newest-first
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable, returns an empty/error payload,
     *         the Circuit Breaker is OPEN, or the tracking code is not
     *         registered with this particular provider.
     */
    RastreioResponse fetch(String codigo);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
