package br.com.cernebr.gateway_nacional.veicular.placa.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.veicular.placa.dto.PlacaResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tertiary placa provider — token-free fallback that scrapes
 * <a href="https://placafipe.com/">placafipe.com</a>.
 *
 * <p>Why this client matters: WDApi and Keplaca both gate behind paid
 * tokens, and the gateway short-circuits Keplaca when its placeholder is
 * detected. With this scraper in the cascade, an unconfigured deploy
 * still resolves placas — and, importantly, gets the {@code codigoFipe}
 * association that neither paid provider exposes. That single field
 * lights up the Avaliação domain ({@code GET /api/v1/avaliacao/placa/...})
 * because the caller no longer needs to supply the FIPE code by hand.</p>
 *
 * <h2>Scraping strategy</h2>
 * placafipe.com renders the vehicle data as label-value pairs whose exact
 * markup (table, definition list, divs) varies across pages. Rather than
 * hard-coding a specific selector that breaks on every redesign, this
 * client builds a map of {@code normalizedLabel → value} by scanning every
 * known label-value structure in the document:
 * <ul>
 *   <li>{@code <tr>} with {@code <th>+<td>} or two {@code <td>}s;</li>
 *   <li>{@code <dl>} with {@code <dt>+<dd>};</li>
 * </ul>
 *
 * <p>Each lookup ({@code "marca"}, {@code "modelo"}, {@code "codigo fipe"}, …)
 * accepts a small alias list to absorb labelling drift. Per-cell parse is
 * isolated in try/catch — a single malformed row never poisons the sweep.
 * If the page loads but no fields can be extracted, the client throws
 * {@link ResourceUnavailableException} so the CB counts it and the cascade
 * runs out cleanly with the aggregate "all-providers" 503.</p>
 *
 * <h2>FlareSolverr fallback</h2>
 * placafipe.com sits behind Cloudflare, so direct Jsoup calls frequently
 * collect a 403. When {@code gateway.flaresolverr.url} is configured, the
 * client delegates the GET to the FlareSolverr sidecar — a headless-Chromium
 * proxy that solves the Cloudflare challenge and returns the resolved HTML.
 * When FlareSolverr is not configured, the client still tries the direct
 * Jsoup path (cheap optimism — sometimes Cloudflare lets it through) and
 * surfaces the failure cleanly when blocked.
 */
@Slf4j
@Component
public class PlacaFipeScraperClient implements PlacaClientProvider {

    public static final String PROVIDER_NAME = "PlacaFipe";

    /** Normalises FIPE codes seen as {@code 5340-0} into the canonical {@code 005340-0}. */
    private static final Pattern FIPE_CODE_PATTERN = Pattern.compile("(\\d{1,6})[-/](\\d)");

    /** Extracts the leading 4-digit year out of values like {@code "2010 / 2011"}. */
    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})");

    private final String baseUrl;
    private final int timeoutMillis;
    private final String userAgent;
    private final FlareSolverrInvoker flareSolverr;

    public PlacaFipeScraperClient(
            @Value("${gateway.placa.placafipe.base-url:https://placafipe.com}") String baseUrl,
            @Value("${gateway.placa.placafipe.timeout-millis:5000}") int timeoutMillis,
            @Value("${gateway.placa.placafipe.user-agent:Mozilla/5.0 (compatible; GatewayNacional/1.0)}") String userAgent,
            FlareSolverrInvoker flareSolverr) {
        this.baseUrl = baseUrl;
        this.timeoutMillis = timeoutMillis;
        this.userAgent = userAgent;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "placaFipeScraperCB", fallbackMethod = "fallback")
    public PlacaResponse fetchByPlaca(String placa) {
        String url = baseUrl + "/placa/" + placa;
        Document doc = flareSolverr.isEnabled() ? fetchViaFlare(url) : fetchDirectly(url);

        Map<String, String> labels = collectLabelValuePairs(doc);
        String marca = pickFirst(labels, "marca");
        String modelo = pickFirst(labels, "modelo");
        if (marca == null && modelo == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "PlacaFipe retornou página, mas nenhum campo de veículo foi extraído (placa não encontrada ou layout alterado).");
        }

        int anoFabricacao = parseYear(pickFirst(labels, "ano fabricacao", "ano de fabricacao", "ano fabric"));
        int anoModelo = parseYear(pickFirst(labels, "ano modelo", "ano do modelo"));
        String chassi = pickFirst(labels, "chassi", "chassis");
        String municipio = pickFirst(labels, "municipio", "cidade");
        String uf = pickFirst(labels, "uf", "estado");
        String codigoFipe = canonicalizeFipeCode(pickFirst(labels, "codigo fipe", "fipe"));

        log.info("PlacaFipe scraped placa={} marca={} modelo={} fipe={}",
                placa, marca, modelo, codigoFipe);

        return new PlacaResponse(
                placa,
                marca,
                modelo,
                anoFabricacao,
                anoModelo,
                ChassiMask.mask(chassi),
                municipio,
                uf,
                codigoFipe
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private PlacaResponse fallback(String placa, Throwable cause) {
        log.warn("PlacaFipe fallback triggered for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "PlacaFipe indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Direct Jsoup fetch — sometimes Cloudflare lets us through; when it
     * doesn't, the 403 surfaces as a {@link ResourceUnavailableException}
     * and the cascade runs out. Activate the FlareSolverr sidecar to bypass.
     */
    private Document fetchDirectly(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .referrer(baseUrl + "/")
                    .timeout(timeoutMillis)
                    .get();
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "PlacaFipe inacessível ou bloqueado por Cloudflare ("
                            + ex.getClass().getSimpleName()
                            + "). Considere ativar o sidecar FlareSolverr (gateway.flaresolverr.url).", ex);
        }
    }

    /**
     * FlareSolverr-mediated fetch — the headless-Chromium sidecar solves
     * the Cloudflare challenge transparently and returns the resolved HTML
     * which we hand to Jsoup for parsing. Failure modes (timeout, FlareSolverr
     * itself unreachable, upstream 4xx) are converted to RUE inside the
     * invoker; we just rethrow.
     */
    private Document fetchViaFlare(String url) {
        FlareSolverrInvoker.FlareResult result = flareSolverr.get(url);
        return Jsoup.parse(result.html(), url);
    }

    /**
     * Builds a defensive {@code label → value} index from every known
     * label-value structure in the document. Label normalization strips
     * accents/case so {@code "Município"} and {@code "MUNICIPIO"} collide
     * onto the same key.
     *
     * <p>Iteration order: insertion-preserved (LinkedHashMap) so that the
     * first occurrence of a given label wins — pages that repeat a label
     * (e.g., quick-summary at top + full table below) yield the topmost
     * value, which is usually the canonical one.</p>
     */
    private Map<String, String> collectLabelValuePairs(Document doc) {
        Map<String, String> map = new LinkedHashMap<>();

        // Pattern 1: <tr><th>label</th><td>value</td></tr>
        Elements ths = doc.select("tr > th");
        for (Element th : ths) {
            try {
                Element td = th.nextElementSibling();
                if (td != null) {
                    putIfAbsentNorm(map, th.text(), td.text());
                }
            } catch (Exception ex) {
                log.debug("PlacaFipe skipped malformed th-td pair: {}", ex.toString());
            }
        }

        // Pattern 2: <tr><td>label</td><td>value</td></tr> (label cell + value cell)
        Elements rows = doc.select("tr");
        for (Element row : rows) {
            try {
                Elements tds = row.select("> td");
                if (tds.size() >= 2) {
                    putIfAbsentNorm(map, tds.get(0).text(), tds.get(1).text());
                }
            } catch (Exception ex) {
                log.debug("PlacaFipe skipped malformed td-td row: {}", ex.toString());
            }
        }

        // Pattern 3: <dl><dt>label</dt><dd>value</dd></dl>
        Elements dts = doc.select("dt");
        for (Element dt : dts) {
            try {
                Element dd = dt.nextElementSibling();
                if (dd != null && dd.tagName().equalsIgnoreCase("dd")) {
                    putIfAbsentNorm(map, dt.text(), dd.text());
                }
            } catch (Exception ex) {
                log.debug("PlacaFipe skipped malformed dt-dd pair: {}", ex.toString());
            }
        }
        return map;
    }

    /**
     * Looks up the first non-blank value in {@code labels} for any of the
     * given canonical keys. Keys are matched accent-insensitive, case-
     * insensitive, whitespace-collapsed.
     */
    private static String pickFirst(Map<String, String> labels, String... canonicalKeys) {
        for (String key : canonicalKeys) {
            String value = labels.get(normalizeKey(key));
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static int parseYear(String value) {
        if (value == null) return 0;
        Matcher matcher = YEAR_PATTERN.matcher(value);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Coerces FIPE codes into the canonical {@code 000000-0} shape that
     * downstream modules expect (FIPE controller regex enforces it).
     * {@code "5340-0"} becomes {@code "005340-0"}; already-canonical input
     * passes through unchanged. Returns {@code null} when no recognizable
     * code can be extracted.
     */
    private static String canonicalizeFipeCode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher matcher = FIPE_CODE_PATTERN.matcher(raw);
        if (!matcher.find()) return null;
        String prefix = matcher.group(1);
        String suffix = matcher.group(2);
        StringBuilder sb = new StringBuilder(8);
        for (int i = prefix.length(); i < 6; i++) sb.append('0');
        sb.append(prefix).append('-').append(suffix);
        return sb.toString();
    }

    private static void putIfAbsentNorm(Map<String, String> map, String label, String value) {
        if (label == null || value == null) return;
        String normLabel = normalizeKey(label);
        if (normLabel.isEmpty()) return;
        String trimmedValue = value.trim();
        if (trimmedValue.isEmpty()) return;
        map.putIfAbsent(normLabel, trimmedValue);
    }

    private static String normalizeKey(String text) {
        if (text == null) return "";
        String stripped = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replace(":", "")
                .trim();
        return stripped.replaceAll("\\s+", " ");
    }
}
