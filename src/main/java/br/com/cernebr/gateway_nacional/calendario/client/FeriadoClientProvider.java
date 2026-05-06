package br.com.cernebr.gateway_nacional.calendario.client;

import br.com.cernebr.gateway_nacional.calendario.dto.FeriadoResponse;

import java.util.List;

/**
 * Contract for any holiday source — external HTTP provider or in-memory
 * calculator. Modeling the offline calculator as a provider lets the
 * orchestrator iterate a single uniform list and reuse the metric/log path.
 */
public interface FeriadoClientProvider {

    /**
     * Returns the federal holiday list for the given Gregorian year.
     *
     * @param ano year (4 digits, already validated at the controller boundary)
     * @return non-null, non-empty list of national holidays
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when an HTTP provider is unreachable, returns empty/error payload,
     *         or the Circuit Breaker is OPEN. The in-memory calculator never
     *         throws this exception — it is the safety net of last resort.
     */
    List<FeriadoResponse> fetch(int ano);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
