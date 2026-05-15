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
 * Marketplace scraper for Webmotors (https://www.webmotors.com.br).
 *
 * <p>Webmotors é uma SPA Next.js fortemente protegida (Cloudflare + bot
 * detection F5 + JS challenge). Jsoup direto recebe a página de challenge,
 * então o scraper depende obrigatoriamente do {@link FlareSolverrInvoker}
 * para resolver o desafio e devolver o HTML pós-hidratação.</p>
 *
 * <h2>Estrutura real</h2>
 * <p>O Webmotors hidrata o listing client-side através de um blob JSON
 * embutido no SSR — o estado inicial vive em {@code __NEXT_DATA__} dentro
 * de um {@code <script id="__NEXT_DATA__" type="application/json">}. Os
 * cards de anúncio (campo {@code SearchResults} ou {@code searchResults})
 * carregam {@code Prices.Price} (preço de venda) e
 * {@code Prices.SearchPrice} (faixa). Para evitar contrato frágil com o
 * blob, o scraper extrai todos os números BRL formatados no blob via regex
 * e fallback para varrer o DOM renderizado quando o JSON não casar.</p>
 *
 * <h2>Roteamento geográfico</h2>
 * <p>O Webmotors expõe filtros geográficos via query string
 * ({@code estadoCidade=SP+CAMPINAS}). O template é configurável por env;
 * sem {@code uf} a URL nacional é mantida intacta — degradação graciosa.</p>
 *
 * <h2>Semântica de falha</h2>
 * <ul>
 *   <li>FlareSolverr desligado → {@link ResourceUnavailableException} para
 *       acionar o fail-soft no orquestrador (o Webmotors sem FlareSolverr
 *       responde challenge, nunca dados úteis).</li>
 *   <li>HTTP ≥ 400 / FlareSolverr {@code status: error} → propagado pelo
 *       invoker como {@link ResourceUnavailableException}, contabilizado no
 *       CB {@code webmotorsScraperCB}.</li>
 *   <li>Página carregou mas nenhum preço foi extraído → falha (seletor /
 *       contrato de blob obsoleto).</li>
 * </ul>
 */
@Slf4j
@Component
public class WebmotorsScraperClient implements MercadoClientProvider {

    public static final String PROVIDER_NAME = "Webmotors";
    static final String FLARE_REQUIRED_MESSAGE =
            "FlareSolverr não está configurado — Webmotors depende do sidecar para resolver o desafio Cloudflare.";

    /** Pool determinístico de User-Agents — rotacionado por cursor atômico para diluir fingerprint. */
    static final List<String> USER_AGENT_POOL = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; rv:121.0) Gecko/20100101 Firefox/121.0"
    );

    /** Captura {@code "Price":47350} ou {@code "Price":47350.00} no blob JSON. */
    private static final Pattern PRICE_FIELD_PATTERN = Pattern.compile(
            "\"(?:Price|UniqueSellingPoint|PriceUnique|SearchPrice|PriceInfo)\"\\s*:\\s*([0-9]+(?:\\.[0-9]{1,2})?)");

    /**
     * Fallback BRL pattern — varre o body quando o blob JSON está ausente
     * (ex.: o site mudou o nome do envelope ou o SSR não embutiu o
     * estado inicial). Mesma heurística do {@link ScraperSupport}.
     */
    private static final List<String> DOM_PRICE_SELECTORS = List.of(
            "[data-testid='price']",
            "[data-test='price']",
            "[class*='AdCard__Price']",
            "[class*='price']",
            "span:contains(R$)",
            "strong:contains(R$)"
    );

    private final AtomicInteger uaCursor = new AtomicInteger(0);

    private final String baseUrl;
    private final String searchPath;
    private final String regionQueryTemplate;
    private final BigDecimal minPrice;
    private final BigDecimal maxPrice;
    private final FlareSolverrInvoker flareSolverr;

    public WebmotorsScraperClient(
            @Value("${gateway.avaliacao.webmotors.base-url:https://www.webmotors.com.br}") String baseUrl,
            @Value("${gateway.avaliacao.webmotors.search-path:/carros/estoque/{marca}/{modelo}?anoDe={ano}&anoAte={ano}}") String searchPath,
            @Value("${gateway.avaliacao.webmotors.region-query-template:&estadoCidade={uf}+{cidade}}") String regionQueryTemplate,
            @Value("${gateway.avaliacao.webmotors.min-price:3000}") BigDecimal minPrice,
            @Value("${gateway.avaliacao.webmotors.max-price:1500000}") BigDecimal maxPrice,
            FlareSolverrInvoker flareSolverr) {
        this.baseUrl = baseUrl;
        this.searchPath = searchPath;
        this.regionQueryTemplate = regionQueryTemplate;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "webmotorsScraperCB", fallbackMethod = "fallback")
    public List<BigDecimal> fetchPrecos(String marca, String modelo, int ano, String uf, String cidade) {
        String url = buildSearchUrl(marca, modelo, ano, uf, cidade);

        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }

        String userAgent = nextUserAgent();
        FlareSolverrInvoker.FlareResult result;
        try {
            result = flareSolverr.get(url);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Webmotors inacessível via FlareSolverr (" + ex.getClass().getSimpleName() + ").", ex);
        }

        String html = result.html();
        if (html == null || html.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Webmotors retornou body vazio mesmo via FlareSolverr.");
        }

        List<BigDecimal> precos = extractPrices(html);
        if (precos.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Webmotors retornou página, mas nenhum preço foi extraído (blob/selector obsoleto?).");
        }
        log.info("Webmotors scraped {} prices for {}-{}-{} [uf={} cidade={} ua={}]",
                precos.size(), marca, modelo, ano, uf, cidade, userAgent);
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

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<BigDecimal> fallback(String marca, String modelo, int ano,
                                      String uf, String cidade, Throwable cause) {
        log.warn("Webmotors fallback triggered for {}-{}-{} [uf={}] cause={}",
                marca, modelo, ano, uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Webmotors indisponível ou Circuit Breaker aberto.", cause);
    }

    private String regionalizeQuery(String uf, String cidade) {
        if (uf == null || uf.isBlank()) {
            return "";
        }
        String ufUpper = uf.toUpperCase(Locale.ROOT);
        String cidadeToken = (cidade != null && !cidade.isBlank())
                ? cidade.trim().toUpperCase(Locale.ROOT).replace(' ', '-')
                : "";
        return regionQueryTemplate
                .replace("{uf}", ufUpper)
                .replace("{cidade}", cidadeToken);
    }

    /**
     * Extração em duas frentes:
     * <ol>
     *   <li>regex sobre o blob {@code __NEXT_DATA__} buscando campos
     *       {@code Price}/{@code SearchPrice}/{@code UniqueSellingPoint};</li>
     *   <li>fallback para varredura DOM (selectors {@link #DOM_PRICE_SELECTORS}
     *       e regex BRL).</li>
     * </ol>
     * Resultado deduplicado via {@link LinkedHashSet} para preservar ordem
     * de aparição (útil em debug) sem inflar a amostra com mesma chamada
     * repetindo o mesmo card.
     */
    List<BigDecimal> extractPrices(String html) {
        Set<BigDecimal> collected = new LinkedHashSet<>();
        collectFromJsonBlob(html, collected);
        if (collected.isEmpty()) {
            collectFromDom(html, collected);
        }
        return new ArrayList<>(collected);
    }

    private void collectFromJsonBlob(String html, Set<BigDecimal> sink) {
        Matcher m = PRICE_FIELD_PATTERN.matcher(html);
        while (m.find()) {
            String raw = m.group(1);
            try {
                BigDecimal value = new BigDecimal(raw);
                if (value.compareTo(minPrice) >= 0 && value.compareTo(maxPrice) <= 0) {
                    sink.add(value);
                }
            } catch (NumberFormatException ignored) {
                // ignore single bad match, keep sweeping
            }
        }
    }

    private void collectFromDom(String html, Set<BigDecimal> sink) {
        try {
            Document document = Jsoup.parse(html);
            for (String selector : DOM_PRICE_SELECTORS) {
                try {
                    Elements elements = document.select(selector);
                    for (Element element : elements) {
                        BigDecimal price = ScraperSupport.tryParseBrl(element.text());
                        if (price != null) {
                            sink.add(price);
                        }
                    }
                    if (!sink.isEmpty()) {
                        return;
                    }
                } catch (Exception ex) {
                    log.debug("Webmotors DOM selector '{}' failed: {}", selector, ex.toString());
                }
            }
            // último recurso: varredura textual ampla
            for (String token : document.body().text().split("\\s{2,}")) {
                BigDecimal price = ScraperSupport.tryParseBrl(token);
                if (price != null) {
                    sink.add(price);
                }
            }
        } catch (Exception ex) {
            log.debug("Webmotors body sweep failed: {}", ex.toString());
        }
    }

    String nextUserAgent() {
        int next = uaCursor.getAndIncrement();
        return USER_AGENT_POOL.get(Math.floorMod(next, USER_AGENT_POOL.size()));
    }
}
