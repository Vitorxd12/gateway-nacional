package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.saude.dto.RepasseFnsResponse;

import java.util.List;

/**
 * Contract for any FNS (Fundo Nacional de Saúde) integration. Implementations
 * are wrapped by Resilience4j Circuit Breakers so that brittle gov.br upstreams
 * degrade gracefully — a portal change or anti-bot block trips the CB, and
 * the orchestrator surfaces a clean 503 instead of a stack trace.
 */
public interface FnsClientProvider {

    /**
     * Resolves all financial repasses for a given {ibge6, yyyy-MM} pair.
     * Returning an empty list is treated as a failure by the orchestrator
     * (likely a closed cookie window or anti-bot block); throw a
     * {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
     * for unequivocal failures so the metrics and CB count it correctly.
     *
     * @param ibge6      6-digit municipal IBGE code (already truncated by the controller)
     * @param ano        4-digit year extracted from the {@code yyyy-MM} competency
     * @param mes        month number 1–12 extracted from the {@code yyyy-MM} competency
     * @param competencia full {@code yyyy-MM} string, propagated to the DTO
     */
    List<RepasseFnsResponse> fetchRepasses(String ibge6, int ano, int mes, String competencia);

    String providerName();
}
