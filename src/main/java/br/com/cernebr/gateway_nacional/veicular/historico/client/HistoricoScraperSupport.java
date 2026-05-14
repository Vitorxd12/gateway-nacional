package br.com.cernebr.gateway_nacional.veicular.historico.client;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Shared utilities for free-tier vehicle-history scrapers:
 * <ul>
 *   <li>User-Agent rotation to spread the fingerprint footprint across calls
 *       (single static UA accelerates IP-level rate limits on consumer sites);</li>
 *   <li>Accent-insensitive normalization of labels so regex anchors hit
 *       {@code "Leilão"} and {@code "LEILAO"} alike;</li>
 *   <li>Pre-compiled marker patterns for leilão and sinistro evidence.</li>
 * </ul>
 *
 * <p>All helpers swallow malformed input (return {@code null} / {@code false}
 * by design): a scraper must keep its parse layer non-throwing — the
 * orchestrator treats an exception as "source failed" and drops it from
 * {@code fontesConsultadas}. Sentinel returns let the scraper convert
 * "no evidence" into a clean {@link HistoricoEvidencia}.</p>
 */
public final class HistoricoScraperSupport {

    /** Rotated per call. Stable, identifiable, no claim of impersonation. */
    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 GatewayNacional/1.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15 GatewayNacional/1.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36 GatewayNacional/1.0"
    );

    /**
     * Leilão markers — anchored on words a Brazilian auction listing uses
     * verbatim. Pattern is intentionally broad (lots of free-tier sources
     * rebadge auction houses), but constrained to a word boundary on each
     * side so {@code "leilao do imovel"} doesn't false-positive a vehicle
     * page that mentions an unrelated leilão elsewhere.
     */
    private static final Pattern LEILAO_PATTERN = Pattern.compile(
            "\\b(leilao|leilao judicial|leilao extrajudicial|copart|sodre santoro|salvado|salvados|recuperavel|recuperacao de veiculo|sinistrado em leilao)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Sinistro markers — words the gov.br databases and insurer-fed feeds
     * use for total-loss / claim history. {@code "perda total"} and
     * {@code "salvado"} are the canonical anchors; the rest catches drift.
     */
    private static final Pattern SINISTRO_PATTERN = Pattern.compile(
            "\\b(sinistro|sinistrado|perda total|perda parcial|indenizacao integral|seguradora|salvado|salvados|recuperac[aã]o de leil[aã]o)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private HistoricoScraperSupport() {
    }

    /**
     * Picks one UA from the pool uniformly at random per call. The pool size
     * is small by design — 3 stable strings keep request-fingerprint hygiene
     * without pretending to be a real browser.
     */
    public static String pickUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /**
     * Accent-stripped, lowercase, whitespace-collapsed form of {@code raw}
     * suitable for keyword matching. {@code "Leilão Judicial"} becomes
     * {@code "leilao judicial"}. Returns the empty string when input is null.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String stripped = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        return stripped.replaceAll("\\s+", " ").trim();
    }

    /** True when {@code normalizedText} contains any leilão marker. */
    public static boolean matchesLeilao(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) return false;
        return LEILAO_PATTERN.matcher(normalizedText).find();
    }

    /** True when {@code normalizedText} contains any sinistro marker. */
    public static boolean matchesSinistro(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) return false;
        return SINISTRO_PATTERN.matcher(normalizedText).find();
    }

    /**
     * Cuts a one-line excerpt around the first marker hit — feeds the
     * {@code detalhesLeilao} audit field on the consolidated DTO so the
     * operator can sanity-check without re-querying the upstream.
     *
     * @return {@code null} when no marker fires.
     */
    public static String excerptAroundMarker(String normalizedText, int radius) {
        if (normalizedText == null || normalizedText.isEmpty()) return null;
        var matcher = LEILAO_PATTERN.matcher(normalizedText);
        int idx;
        if (matcher.find()) {
            idx = matcher.start();
        } else {
            matcher = SINISTRO_PATTERN.matcher(normalizedText);
            if (!matcher.find()) return null;
            idx = matcher.start();
        }
        int from = Math.max(0, idx - radius);
        int to = Math.min(normalizedText.length(), idx + radius);
        String slice = normalizedText.substring(from, to).trim();
        return slice.length() > 240 ? slice.substring(0, 240) + "…" : slice;
    }
}
