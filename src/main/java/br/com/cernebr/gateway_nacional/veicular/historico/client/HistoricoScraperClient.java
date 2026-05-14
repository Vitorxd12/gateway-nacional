package br.com.cernebr.gateway_nacional.veicular.historico.client;

/**
 * Contract for any free-tier vehicle-history scraper. Each implementation
 * targets a single upstream (leilaofree.com.br, consultar-placa.com, etc.)
 * and is wrapped by Resilience4j {@code @CircuitBreaker} at the method level.
 *
 * <p>Failure semantics: throw {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
 * when the upstream cannot be reached, returns garbage, or the page layout
 * has drifted past recognition. The orchestrator absorbs the throw and drops
 * the source from {@code fontesConsultadas} — it never bubbles to the user.</p>
 *
 * <p>"Nada consta" responses (the source answered cleanly, page renders but
 * no markers fire) MUST be returned as a clean {@link HistoricoEvidencia}
 * with both booleans {@code false} — see {@link HistoricoEvidencia#clean(String)}.</p>
 */
public interface HistoricoScraperClient {

    HistoricoEvidencia consultar(String placa);

    String providerName();
}
