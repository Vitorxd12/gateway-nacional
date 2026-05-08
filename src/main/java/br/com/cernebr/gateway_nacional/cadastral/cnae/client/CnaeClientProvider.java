package br.com.cernebr.gateway_nacional.cadastral.cnae.client;

import br.com.cernebr.gateway_nacional.cadastral.cnae.dto.CnaeResponse;

import java.util.Optional;

/**
 * Contract for any CNAE (Classificação Nacional de Atividades Econômicas)
 * integration. Implementations are wrapped by Resilience4j and convert
 * provider-specific payloads into the unified {@link CnaeResponse}.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>Returns {@link Optional#empty()} when the upstream definitively
 *       answers "no such code" — service translates that across all
 *       providers into a {@code ResourceNotFoundException} (404).</li>
 *   <li>Throws {@code ResourceUnavailableException} on infra-level failure
 *       (network, 5xx, CB OPEN) so the cascade triggers fallback.</li>
 * </ul>
 */
public interface CnaeClientProvider {

    /**
     * Resolves a single CNAE subclass entry.
     *
     * @param codigo subclass code with 7 digits ({@code 6422100}). The
     *               implementation tolerates separators ({@code "6422-1/00"})
     *               by stripping non-digit characters.
     */
    Optional<CnaeResponse> findByCodigo(String codigo);

    /** Stable provider identifier for logging and observability tags. */
    String providerName();
}
