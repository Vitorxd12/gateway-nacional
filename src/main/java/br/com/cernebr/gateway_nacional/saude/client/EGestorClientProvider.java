package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.saude.dto.EquipeEGestorResponse;

import java.util.List;

/**
 * Contract for any e-Gestor APS integration. Implementations are wrapped
 * by Resilience4j Circuit Breakers; the orchestrator surfaces a clean 503
 * when the cascade exhausts every provider.
 *
 * <p>Granularity: one call returns every team paid (or scheduled to be
 * paid) for the given {ibge6, yyyy-MM} pair, across all blocks (ESF, ESB,
 * eMulti, ACS).</p>
 */
public interface EGestorClientProvider {

    List<EquipeEGestorResponse> fetchEquipes(String ibge6, int ano, int mes);

    String providerName();
}
