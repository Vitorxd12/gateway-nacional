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
 *
 * <p><b>Roteamento geográfico:</b> {@code uf} e {@code cidade} são opcionais.
 * Quando informados, cada implementação reescreve a URL alvo para o padrão
 * geográfico do seu marketplace (a OLX usa subdomínio de estado, a MobiAuto
 * usa query params, etc.). Quando {@code uf} chega {@code null}/vazio, a
 * implementação <b>deve</b> degradar graciosamente para a busca nacional —
 * nunca falhar por ausência de recorte regional.</p>
 */
public interface MercadoClientProvider {

    /**
     * Resolves a list of advertised prices for a given {marca, modelo, ano}
     * tuple, optionally narrowed to a {@code uf}/{@code cidade}. Returning an
     * empty list is treated as a failure by the orchestrator (likely a stale
     * CSS selector); throw a
     * {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
     * for unequivocal failures (network, 5xx, parse-zero) so the cascade
     * counts it correctly in the metrics and CB.
     *
     * @param uf     sigla da UF (ex: {@code "SP"}) ou {@code null}/vazio para busca nacional
     * @param cidade nome da cidade ou {@code null}/vazio — só aplicado quando {@code uf} também vier
     */
    List<BigDecimal> fetchPrecos(String marca, String modelo, int ano, String uf, String cidade);

    /**
     * Builds the same URL used by {@link #fetchPrecos} so the orchestrator
     * can expose it under {@code linksReferencia}, allowing downstream
     * consumers (and humans) to audit the source — e o recorte geográfico —
     * do snapshot.
     */
    String buildSearchUrl(String marca, String modelo, int ano, String uf, String cidade);

    /**
     * Stable provider identifier used for logging, metrics tags, and links.
     */
    String providerName();
}
