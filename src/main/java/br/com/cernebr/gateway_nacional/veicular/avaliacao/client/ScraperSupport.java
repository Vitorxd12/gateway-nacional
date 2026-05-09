package br.com.cernebr.gateway_nacional.veicular.avaliacao.client;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for marketplace scrapers — slug generation for URL
 * composition and defensive parsing of BRL-formatted prices.
 *
 * <p>Both helpers swallow malformed input (returning {@code null} or empty
 * string) by design — scrapers must not throw at the parse layer; the
 * orchestrator interprets an empty price list as a failed scrape and
 * cascades to the next provider.</p>
 */
final class ScraperSupport {

    /** Captures BRL-formatted amounts like {@code R$ 45.000,00} or {@code 45.000}. */
    private static final Pattern BRL_PRICE_PATTERN =
            Pattern.compile("R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*(?:,[0-9]{1,2})?)");

    /** Heuristic minimum to filter out shipping/membership noise like "R$ 9,90". */
    private static final BigDecimal MIN_VEHICLE_PRICE = new BigDecimal("1000");

    /** Heuristic upper bound to avoid catching badly parsed thousand separators. */
    private static final BigDecimal MAX_VEHICLE_PRICE = new BigDecimal("10000000");

    private ScraperSupport() {
    }

    /**
     * Lowercase, ASCII-folded, hyphen-joined token suitable for embedding in
     * marketplace URL paths. {@code "Volkswagen Gol"} becomes {@code "volkswagen-gol"}.
     * Returns the empty string for null/blank input — scrapers should validate
     * upstream rather than relying on this to short-circuit.
     */
    static String slugify(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /**
     * Extracts a BigDecimal from any text that contains a BRL-formatted
     * amount. Returns {@code null} when no match, the amount is below the
     * vehicle-price floor, or the result fails {@link BigDecimal} parsing.
     * Never throws — caller can stream-filter on null.
     */
    static BigDecimal tryParseBrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = BRL_PRICE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String numeric = matcher.group(1)
                .replace(".", "")
                .replace(",", ".");
        try {
            BigDecimal value = new BigDecimal(numeric);
            if (value.compareTo(MIN_VEHICLE_PRICE) < 0 || value.compareTo(MAX_VEHICLE_PRICE) > 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
