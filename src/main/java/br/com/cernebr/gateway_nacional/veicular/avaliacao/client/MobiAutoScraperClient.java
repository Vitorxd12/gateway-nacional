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
 * Marketplace scraper for MobiAuto (https://www.mobiauto.com.br).
 *
 * <p>Mirrors the defensive strategy of {@link OlxScraperClient}: ordered
 * list of selectors, per-element parse isolation, body-text fallback,
 * empty-result-as-failure semantics. Different CB instance ({@code mobiAutoScraperCB})
 * so OLX flapping does not poison MobiAuto's health view, and vice-versa.</p>
 */
@Slf4j
@Component
public class MobiAutoScraperClient implements MercadoClientProvider {

    public static final String PROVIDER_NAME = "MobiAuto";

    private static final List<String> PRICE_SELECTORS = List.of(
            "[data-testid='price']",
            "[class*='price']",
            "[class*='Price']",
            "strong:contains(R$)",
            "span:contains(R$)"
    );

    private final String baseUrl;
    private final String searchPath;
    private final int timeoutMillis;
    private final String userAgent;

    public MobiAutoScraperClient(
            @Value("${gateway.avaliacao.mobiauto.base-url:https://www.mobiauto.com.br}") String baseUrl,
            @Value("${gateway.avaliacao.mobiauto.search-path:/comprar/{marca}/{modelo}/{ano}}") String searchPath,
            @Value("${gateway.avaliacao.mobiauto.timeout-millis:5000}") int timeoutMillis,
            @Value("${gateway.avaliacao.mobiauto.user-agent:Mozilla/5.0 (compatible; GatewayNacional/1.0)}") String userAgent) {
        this.baseUrl = baseUrl;
        this.searchPath = searchPath;
        this.timeoutMillis = timeoutMillis;
        this.userAgent = userAgent;
    }

    @Override
    @CircuitBreaker(name = "mobiAutoScraperCB", fallbackMethod = "fallback")
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
                    "MobiAuto inacessível ou timeout (" + ex.getClass().getSimpleName() + ").", ex);
        }

        List<BigDecimal> precos = extractPrices(document);
        if (precos.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "MobiAuto retornou página, mas nenhum preço foi extraído (seletor obsoleto?).");
        }
        log.info("MobiAuto scraped {} prices for {}-{}-{}", precos.size(), marca, modelo, ano);
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
        log.warn("MobiAuto fallback triggered for {}-{}-{} cause={}", marca, modelo, ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "MobiAuto indisponível ou Circuit Breaker aberto.", cause);
    }

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
                log.debug("MobiAuto selector '{}' failed: {}", selector, ex.toString());
            }
        }
        try {
            for (String token : document.body().text().split("\\s{2,}")) {
                BigDecimal price = ScraperSupport.tryParseBrl(token);
                if (price != null) {
                    collected.add(price);
                }
            }
        } catch (Exception ex) {
            log.debug("MobiAuto body sweep failed: {}", ex.toString());
        }
        return collected.stream().filter(Objects::nonNull).toList();
    }
}
