package br.com.cernebr.gateway_nacional.exception;

import java.io.Serial;

/**
 * Thrown when an upstream provider (ViaCEP, ReceitaWS, etc.) is unreachable
 * or when the associated Circuit Breaker is in OPEN state and short-circuits
 * the call. Maps to HTTP 503 Service Unavailable at the API boundary.
 */
public class ResourceUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String providerName;

    public ResourceUnavailableException(String providerName, String message) {
        super(message);
        this.providerName = providerName;
    }

    public ResourceUnavailableException(String providerName, String message, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
    }

    public String getProviderName() {
        return providerName;
    }
}
