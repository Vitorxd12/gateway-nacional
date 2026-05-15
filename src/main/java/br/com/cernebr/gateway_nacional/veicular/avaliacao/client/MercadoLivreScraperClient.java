package br.com.cernebr.gateway_nacional.veicular.avaliacao.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marketplace scraper for Mercado Livre Veículos (https://carros.mercadolivre.com.br).
 *
 * <p>URL pattern: {@code carros.mercadolivre.com.br/{marca}/{modelo}/ano-{ano}/}.
 * O Mercado Livre tipicamente entrega os preços renderizados em HTML
 * estático (SEO-first), então o caminho preferencial é Jsoup direto com
 * User-Agent rotativo. Quando a detecção bot dispara — Mercado Livre tem
 * um filtro de fingerprint via {@code _d2id} cookie + JS challenge — o
 * scraper escalona automaticamente para o {@link FlareSolverrInvoker}.</p>
 *
 * <h2>Estratégia híbrida</h2>
 * <ol>
 *   <li>Tenta Jsoup direto com User-Agent rotativo e timeout enxuto;</li>
 *   <li>Se a resposta vier vazia, 403/429, ou contiver marcadores típicos
 *       de challenge ({@code "captcha"}, {@code "cf-chl"}, etc.), escalona
 *       para FlareSolverr.</li>
 * </ol>
 *
 * <h2>Roteamento geográfico</h2>
 * <p>O Mercado Livre injeta filtro de estado pelo segmento
 * {@code _PriceRange_HASH_*-OPERATION-ESTADO_{slug}}; quando o operador
 * passa um template customizado via env, ele é respeitado tal qual. Sem
 * UF, mantém-se a busca nacional.</p>
 *
 * <h2>Semântica de falha</h2>
 * <ul>
 *   <li>Jsoup falhou E FlareSolverr indisponível → {@link ResourceUnavailableException}.</li>
 *   <li>Página carregou mas nenhum preço extraído → falha (DOM mudou).</li>
 *   <li>Erros propagam via {@code mercadoLivreScraperCB}.</li>
 * </ul>
 */
@Slf4j
@Component
public class MercadoLivreScraperClient implements MercadoClientProvider {

    public static final String PROVIDER_NAME = "MercadoLivre";

    /** Pool de User-Agents rotativos — diluição de fingerprint para Jsoup direto. */
    static final List<String> USER_AGENT_POOL = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    );

    /** Indicadores típicos de página de challenge / captcha — disparam fallback FlareSolverr. */
    private static final List<String> CHALLENGE_MARKERS = List.of(
            "captcha", "cf-chl", "cloudflare", "Just a moment", "shield-token"
    );

    /** Cards de anúncio MELI — sequência ordered (mais específico → mais permissivo). */
    private static final List<String> PRICE_SELECTORS = List.of(
            "span.andes-money-amount__fraction",
            "[class*='price-tag-fraction']",
            "[class*='ui-search-price__second-line'] span",
            "[class*='andes-money-amount']",
            "span:contains(R$)"
    );

    /** Regex fallback sobre o JSON embutido no SSR — {@code "price":{"amount":47350}}. */
    private static final Pattern JSON_PRICE_PATTERN = Pattern.compile(
            "\"price\"\\s*:\\s*\\{[^}]*?\"amount\"\\s*:\\s*([0-9]+(?:\\.[0-9]{1,2})?)");

    private final AtomicInteger uaCursor = new AtomicInteger(0);

    private final String baseUrl;
    private final String searchPath;
    private final String regionPathTemplate;
    private final int timeoutMillis;
    private final BigDecimal minPrice;
    private final BigDecimal maxPrice;
    private final FlareSolverrInvoker flareSolverr;

    public MercadoLivreScraperClient(
            @Value("${gateway.avaliacao.mercadolivre.base-url:https://carros.mercadolivre.com.br}") String baseUrl,
            @Value("${gateway.avaliacao.mercadolivre.search-path:/{marca}/{modelo}/ano-{ano}/}") String searchPath,
            @Value("${gateway.avaliacao.mercadolivre.region-path-template:_PriceRange_0-9999999*ESTADO_{uf}}") String regionPathTemplate,
            @Value("${gateway.avaliacao.mercadolivre.timeout-millis:7000}") int timeoutMillis,
            @Value("${gateway.avaliacao.mercadolivre.min-price:3000}") BigDecimal minPrice,
            @Value("${gateway.avaliacao.mercadolivre.max-price:1500000}") BigDecimal maxPrice,
            FlareSolverrInvoker flareSolverr) {
        this.baseUrl = baseUrl;
        this.searchPath = searchPath;
        this.regionPathTemplate = regionPathTemplate;
        this.timeoutMillis = timeoutMillis;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "mercadoLivreScraperCB", fallbackMethod = "fallback")
    public List<BigDecimal> fetchPrecos(String marca, String modelo, int ano, String uf, String cidade) {
        String url = buildSearchUrl(marca, modelo, ano, uf, cidade);
        String userAgent = nextUserAgent();

        String html = fetchDirectly(url, userAgent);
        boolean fallbackToFlare = html == null || isChallenge(html);

        if (fallbackToFlare && flareSolverr.isEnabled()) {
            log.info("MercadoLivre: Jsoup direto bloqueado/vazio, escalonando para FlareSolverr.");
            try {
                html = flareSolverr.get(url).html();
            } catch (Exception ex) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "MercadoLivre inacessível mesmo via FlareSolverr (" + ex.getClass().getSimpleName() + ").", ex);
            }
        }

        if (html == null || html.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "MercadoLivre retornou body vazio (Jsoup + FlareSolverr).");
        }

        List<BigDecimal> precos = extractPrices(html);
        if (precos.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "MercadoLivre retornou página, mas nenhum preço foi extraído (DOM/JSON obsoleto?).");
        }
        log.info("MercadoLivre scraped {} prices for {}-{}-{} [uf={} cidade={} ua={}]",
                precos.size(), marca, modelo, ano, uf, cidade, userAgent);
        return precos;
    }

    @Override
    public String buildSearchUrl(String marca, String modelo, int ano, String uf, String cidade) {
        String path = searchPath
                .replace("{marca}", ScraperSupport.slugify(marca))
                .replace("{modelo}", ScraperSupport.slugify(modelo))
                .replace("{ano}", String.valueOf(ano));
        String regional = regionalizeSuffix(uf);
        if (cidade != null && !cidade.isBlank() && regional.isEmpty()) {
            // Sem UF, cidade não tem ancoragem — Mercado Livre exige estado primeiro.
            // Mantém URL nacional para degradação graciosa.
        }
        return baseUrl + path + regional;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<BigDecimal> fallback(String marca, String modelo, int ano,
                                      String uf, String cidade, Throwable cause) {
        log.warn("MercadoLivre fallback triggered for {}-{}-{} [uf={}] cause={}",
                marca, modelo, ano, uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "MercadoLivre indisponível ou Circuit Breaker aberto.", cause);
    }

    private String regionalizeSuffix(String uf) {
        if (uf == null || uf.isBlank()) {
            return "";
        }
        return regionPathTemplate.replace("{uf}", uf.toLowerCase(Locale.ROOT));
    }

    /**
     * Jsoup direto com timeout enxuto. Devolve {@code null} para qualquer
     * falha (network, timeout, 5xx) — o caller decide se escala para
     * FlareSolverr ou falha hard.
     */
    private String fetchDirectly(String url, String userAgent) {
        try {
            return Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .timeout(timeoutMillis)
                    .ignoreHttpErrors(false)
                    .get()
                    .outerHtml();
        } catch (Exception ex) {
            log.debug("MercadoLivre Jsoup direto falhou: {}", ex.toString());
            return null;
        }
    }

    private static boolean isChallenge(String html) {
        if (html == null) return true;
        String lower = html.toLowerCase(Locale.ROOT);
        // Sinal de página de challenge: muito curta + algum marker conhecido.
        if (lower.length() < 5000) {
            for (String marker : CHALLENGE_MARKERS) {
                if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    List<BigDecimal> extractPrices(String html) {
        Set<BigDecimal> collected = new LinkedHashSet<>();
        Document document;
        try {
            document = Jsoup.parse(html);
        } catch (Exception ex) {
            log.debug("MercadoLivre Jsoup.parse falhou: {}", ex.toString());
            document = null;
        }
        if (document != null) {
            for (String selector : PRICE_SELECTORS) {
                try {
                    Elements elements = document.select(selector);
                    for (Element element : elements) {
                        BigDecimal price = ScraperSupport.tryParseBrl("R$ " + element.text());
                        if (price == null) {
                            price = ScraperSupport.tryParseBrl(element.text());
                        }
                        if (price == null) {
                            price = parseFractionToken(element.text());
                        }
                        if (price != null && inRange(price)) {
                            collected.add(price);
                        }
                    }
                    if (!collected.isEmpty()) {
                        break;
                    }
                } catch (Exception ex) {
                    log.debug("MercadoLivre selector '{}' falhou: {}", selector, ex.toString());
                }
            }
        }
        // Fallback: regex sobre blob JSON SSR.
        if (collected.isEmpty()) {
            Matcher m = JSON_PRICE_PATTERN.matcher(html);
            while (m.find()) {
                try {
                    BigDecimal value = new BigDecimal(m.group(1));
                    if (inRange(value)) {
                        collected.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // sigaframe
                }
            }
        }
        return new ArrayList<>(collected);
    }

    /**
     * Mercado Livre publica o preço quebrado em {@code span.andes-money-amount__fraction}
     * (apenas a parte inteira, ex.: {@code 47.350}). Converte esse token
     * em BigDecimal aplicando o separador brasileiro de milhar.
     */
    private static BigDecimal parseFractionToken(String token) {
        if (token == null) return null;
        String cleaned = token.replaceAll("[^0-9.,]", "");
        if (cleaned.isBlank()) return null;
        cleaned = cleaned.replace(".", "").replace(",", ".");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean inRange(BigDecimal value) {
        return value != null
                && value.compareTo(minPrice) >= 0
                && value.compareTo(maxPrice) <= 0;
    }

    String nextUserAgent() {
        int next = uaCursor.getAndIncrement();
        return USER_AGENT_POOL.get(Math.floorMod(next, USER_AGENT_POOL.size()));
    }
}
