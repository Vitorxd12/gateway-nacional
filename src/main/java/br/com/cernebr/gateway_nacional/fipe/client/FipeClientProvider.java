package br.com.cernebr.gateway_nacional.fipe.client;

import br.com.cernebr.gateway_nacional.fipe.dto.FipePrecoResponse;

/**
 * Contract for any FIPE quote provider integration. Implementations are
 * wrapped by Resilience4j (Circuit Breaker / TimeLimiter) and convert
 * provider-specific payloads into the unified {@link FipePrecoResponse}.
 */
public interface FipeClientProvider {

    /**
     * Resolves the FIPE reference price for the given vehicle.
     *
     * @param codigoFipe FIPE code in the canonical format {@code 000000-0}
     *                   (already validated at the controller boundary).
     * @param anoModelo  4-digit model year, or {@code "32000"} for Zero KM
     *                   (FIPE's special "current year" sentinel).
     * @return unified FIPE quote payload
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable, returns an empty/error payload,
     *         the requested year is not in the upstream catalogue, or the
     *         Circuit Breaker is OPEN.
     */
    FipePrecoResponse fetchPreco(String codigoFipe, String anoModelo);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
