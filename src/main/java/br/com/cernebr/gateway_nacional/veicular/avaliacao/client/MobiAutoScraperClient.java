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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Marketplace scraper for MobiAuto (https://www.mobiauto.com.br).
 *
 * <p>Mirrors the defensive strategy of {@link OlxScraperClient}: ordered
 * list of selectors, per-element parse isolation, body-text fallback,
 * empty-result-as-failure semantics. Different CB instance ({@code mobiAutoScraperCB})
 * so OLX flapping does not poison MobiAuto's health view, and vice-versa.</p>
 *
 * <p><b>Roteamento geográfico:</b> ao contrário da OLX (subdomínio), a
 * MobiAuto segmenta região via <i>query params</i> no mesmo host. Quando o
 * chamador passa {@code uf} (e opcionalmente {@code cidade}), o
 * {@code region-query-template} é anexado à URL — placeholders de campo
 * vazio são podados. Sem {@code uf}, a URL nacional é mantida intacta
 * (fallback gracioso). O template é configurável por env.</p>
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
    private final String regionQueryTemplate;
    private final int timeoutMillis;
    private final String userAgent;

    public MobiAutoScraperClient(
            @Value("${gateway.avaliacao.mobiauto.base-url:https://www.mobiauto.com.br}") String baseUrl,
            @Value("${gateway.avaliacao.mobiauto.search-path:/comprar/{marca}/{modelo}/{ano}}") String searchPath,
            @Value("${gateway.avaliacao.mobiauto.region-query-template:?estado={uf}&cidade={cidade}}") String regionQueryTemplate,
            @Value("${gateway.avaliacao.mobiauto.timeout-millis:5000}") int timeoutMillis,
            @Value("${gateway.avaliacao.mobiauto.user-agent:Mozilla/5.0 (compatible; GatewayNacional/1.0)}") String userAgent) {
        this.baseUrl = baseUrl;
        this.searchPath = searchPath;
        this.regionQueryTemplate = regionQueryTemplate;
        this.timeoutMillis = timeoutMillis;
        this.userAgent = userAgent;
    }

    @Override
    @CircuitBreaker(name = "mobiAutoScraperCB", fallbackMethod = "fallback")
    public List<BigDecimal> fetchPrecos(String marca, String modelo, int ano, String uf, String cidade) {
        String url = buildSearchUrl(marca, modelo, ano, uf, cidade);

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
        log.info("MobiAuto scraped {} prices for {}-{}-{} [uf={} cidade={}]",
                precos.size(), marca, modelo, ano, uf, cidade);
        return precos;
    }

    @Override
    public String buildSearchUrl(String marca, String modelo, int ano, String uf, String cidade) {
        String path = searchPath
                .replace("{marca}", ScraperSupport.slugify(marca))
                .replace("{modelo}", ScraperSupport.slugify(modelo))
                .replace("{ano}", String.valueOf(ano));
        return baseUrl + path + regionalizeQuery(uf, cidade);
    }

    /**
     * Builds the regional query string from {@code region-query-template}.
     * Returns the empty string for a national search (no UF). When a UF is
     * present but the city is not, the {@code cidade} segment is pruned so
     * the URL never carries a dangling {@code cidade=} param.
     */
    private String regionalizeQuery(String uf, String cidade) {
        if (uf == null || uf.isBlank()) {
            return "";
        }
        String query = regionQueryTemplate.replace("{uf}", uf.toLowerCase(Locale.ROOT));
        if (cidade != null && !cidade.isBlank()) {
            return query.replace("{cidade}", URLEncoder.encode(cidade.trim(), StandardCharsets.UTF_8));
        }
        // Drop a "&cidade={cidade}" / "cidade={cidade}&" fragment when no city was supplied.
        return query
                .replaceAll("[&?]cidade=\\{cidade}", "")
                .replace("{cidade}", "");
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<BigDecimal> fallback(String marca, String modelo, int ano,
                                      String uf, String cidade, Throwable cause) {
        log.warn("MobiAuto fallback triggered for {}-{}-{} [uf={}] cause={}",
                marca, modelo, ano, uf, cause.toString());
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
