package br.com.cernebr.gateway_nacional.placa.client;

import br.com.cernebr.gateway_nacional.placa.dto.PlacaResponse;

/**
 * Contract for any vehicle-by-placa provider integration. Implementations
 * are wrapped by Resilience4j (Circuit Breaker / TimeLimiter) and convert
 * provider-specific payloads into the unified {@link PlacaResponse} —
 * including the privacy-preserving chassi masking.
 */
public interface PlacaClientProvider {

    /**
     * Resolves vehicle data from the given license plate.
     *
     * @param placa canonical uppercase form without hyphen, e.g.
     *              {@code "ABC1D23"} (Mercosul) or {@code "ABC1234"}
     *              (legacy). Already normalized at the controller boundary.
     * @return unified vehicle payload with chassi pre-masked
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable, returns an empty/error
     *         payload, the credentials are missing, or the Circuit Breaker
     *         is OPEN.
     */
    PlacaResponse fetchByPlaca(String placa);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
