package br.com.cernebr.gateway_nacional.veicular.avaliacao.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * Contract for any real-market price-discovery integration (scrapers,
 * partner APIs, internal feeds). Implementations are wrapped by Resilience4j
 * Circuit Breakers so that brittle HTML scraping can degrade gracefully —
 * when a marketplace changes its DOM, the corresponding CB trips, the
 * cascade in {@code AvaliacaoService} continues, and the gateway keeps
 * serving valuations from the remaining providers.
 */
public interface MercadoClientProvider {

    /**
     * Resolves a list of advertised prices for a given {marca, modelo, ano}
     * tuple. Returning an empty list is treated as a failure by the orchestrator
     * (likely a stale CSS selector); throw a
     * {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
     * for unequivocal failures (network, 5xx, parse-zero) so the cascade
     * counts it correctly in the metrics and CB.
     */
    List<BigDecimal> fetchPrecos(String marca, String modelo, int ano);

    /**
     * Builds the same URL used by {@link #fetchPrecos(String, String, int)} so
     * the orchestrator can expose it under {@code linksReferencia}, allowing
     * downstream consumers (and humans) to audit the source of the snapshot.
     */
    String buildSearchUrl(String marca, String modelo, int ano);

    /**
     * Stable provider identifier used for logging, metrics tags, and links.
     */
    String providerName();
}
