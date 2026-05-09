package br.com.cernebr.gateway_nacional.veicular.avaliacao.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Marketplace scraper for OLX Autos (https://www.olx.com.br).
 *
 * <p>OLX renders prices inside ad-card components whose class names are
 * compiled and rotated frequently. To stay resilient, this scraper:
 * <ol>
 *   <li>tries a list of historically-valid CSS selectors targeting price
 *       elements (data-testid first, semantic classes next);</li>
 *   <li>falls back to scanning the entire body for {@code R$} patterns
 *       when no selector hits;</li>
 *   <li>wraps the parse step per element so a single malformed node
 *       cannot poison the whole page.</li>
 * </ol>
 *
 * <p>If the page loads but yields zero parseable prices, the scraper throws
 * {@link ResourceUnavailableException} — the orchestrator treats that as
 * a failure, the CB counts it, and the cascade tries the next marketplace.
 * That is the desired posture: we'd rather flag a stale selector than
 * silently report "no listings found".</p>
 */
@Slf4j
@Component
public class OlxScraperClient implements MercadoClientProvider {

    public static final String PROVIDER_NAME = "OLX";

    /** Ordered list — most specific to most permissive. */
    private static final List<String> PRICE_SELECTORS = List.of(
            "[data-testid='adcard-price']",
            "[data-lurker-detail='price']",
            ".olx-ad-card__price",
            "span[class*='price']",
            "h3[class*='Price']"
    );

    private final String baseUrl;
    private final String searchPath;
    private final int timeoutMillis;
    private final String userAgent;

    public OlxScraperClient(
            @Value("${gateway.avaliacao.olx.base-url:https://www.olx.com.br}") String baseUrl,
            @Value("${gateway.avaliacao.olx.search-path:/autos-e-pecas/carros-vans-e-utilitarios/{marca}/{modelo}/ano-{ano}}") String searchPath,
            @Value("${gateway.avaliacao.olx.timeout-millis:5000}") int timeoutMillis,
            @Value("${gateway.avaliacao.olx.user-agent:Mozilla/5.0 (compatible; GatewayNacional/1.0)}") String userAgent) {
        this.baseUrl = baseUrl;
        this.searchPath = searchPath;
        this.timeoutMillis = timeoutMillis;
        this.userAgent = userAgent;
    }

    @Override
    @CircuitBreaker(name = "olxScraperCB", fallbackMethod = "fallback")
    public List<BigDecimal> fetchPrecos(String marca, String modelo, int ano) {
        String url = buildSearchUrl(marca, modelo, ano);

        Document document;
        try {
            document = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(timeoutMillis)
                    .get();
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "OLX inacessível ou timeout (" + ex.getClass().getSimpleName() + ").", ex);
        }

        List<BigDecimal> precos = extractPrices(document);
        if (precos.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "OLX retornou página, mas nenhum preço foi extraído (seletor obsoleto?).");
        }
        log.info("OLX scraped {} prices for {}-{}-{}", precos.size(), marca, modelo, ano);
        return precos;
    }

    @Override
    public String buildSearchUrl(String marca, String modelo, int ano) {
        String path = searchPath
                .replace("{marca}", ScraperSupport.slugify(marca))
                .replace("{modelo}", ScraperSupport.slugify(modelo))
                .replace("{ano}", String.valueOf(ano));
        return baseUrl + path;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<BigDecimal> fallback(String marca, String modelo, int ano, Throwable cause) {
        log.warn("OLX fallback triggered for {}-{}-{} cause={}", marca, modelo, ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "OLX indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Defensive extraction — every parse is isolated in its own try/catch so
     * a malformed element never breaks the whole sweep.
     */
    private List<BigDecimal> extractPrices(Document document) {
        List<BigDecimal> collected = new ArrayList<>();
        for (String selector : PRICE_SELECTORS) {
            try {
                Elements elements = document.select(selector);
                for (Element element : elements) {
                    BigDecimal price = ScraperSupport.tryParseBrl(element.text());
                    if (price != null) {
                        collected.add(price);
                    }
                }
                if (!collected.isEmpty()) {
                    return collected;
                }
            } catch (Exception ex) {
                log.debug("OLX selector '{}' failed: {}", selector, ex.toString());
            }
        }
        // Last-resort fallback: regex sweep over the entire body text.
        try {
            for (String token : document.body().text().split("\\s{2,}")) {
                BigDecimal price = ScraperSupport.tryParseBrl(token);
                if (price != null) {
                    collected.add(price);
                }
            }
        } catch (Exception ex) {
            log.debug("OLX body sweep failed: {}", ex.toString());
        }
        return collected.stream().filter(Objects::nonNull).toList();
    }
}
