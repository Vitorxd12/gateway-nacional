package br.com.cernebr.gateway_nacional.exception;

import java.io.Serial;

/**
 * Thrown when a lookup completes successfully but the requested resource
 * does not exist on any of the upstream catalogues — e.g., an NCM code
 * not registered in the Mercosul nomenclature.
 *
 * <p>Maps to HTTP 404 at the API boundary, cleanly distinguishing
 * "we know that does not exist" from {@link ResourceUnavailableException}
 * (which maps to 503 — "we could not check, the upstream is down").
 * The semantic split lets API consumers act differently: a 404 is a
 * permanent answer that should not be retried, while a 503 is transient
 * and warrants exponential backoff.</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String resourceType;

    public ResourceNotFoundException(String resourceType, String message) {
        super(message);
        this.resourceType = resourceType;
    }

    public String getResourceType() {
        return resourceType;
    }
}
