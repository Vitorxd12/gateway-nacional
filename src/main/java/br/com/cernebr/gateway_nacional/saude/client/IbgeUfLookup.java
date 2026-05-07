package br.com.cernebr.gateway_nacional.saude.client;

import java.util.Map;

/**
 * Maps the 2-digit numeric UF prefix found at the start of every Brazilian
 * IBGE municipal code to the canonical 2-letter UF abbreviation. The FNS
 * portal expects the abbreviation ({@code "BA"}); the e-Gestor portal
 * expects the numeric code (already obtained via {@code ibge.substring(0,2)}).
 *
 * <p>Static, complete, no I/O. The 27 Brazilian federative units are
 * enumerated below — any UF code outside the table indicates a malformed
 * IBGE and the caller should reject upstream of this lookup.</p>
 */
final class IbgeUfLookup {

    private static final Map<String, String> CODE_TO_UF = Map.ofEntries(
            Map.entry("11", "RO"), Map.entry("12", "AC"), Map.entry("13", "AM"),
            Map.entry("14", "RR"), Map.entry("15", "PA"), Map.entry("16", "AP"),
            Map.entry("17", "TO"), Map.entry("21", "MA"), Map.entry("22", "PI"),
            Map.entry("23", "CE"), Map.entry("24", "RN"), Map.entry("25", "PB"),
            Map.entry("26", "PE"), Map.entry("27", "AL"), Map.entry("28", "SE"),
            Map.entry("29", "BA"), Map.entry("31", "MG"), Map.entry("32", "ES"),
            Map.entry("33", "RJ"), Map.entry("35", "SP"), Map.entry("41", "PR"),
            Map.entry("42", "SC"), Map.entry("43", "RS"), Map.entry("50", "MS"),
            Map.entry("51", "MT"), Map.entry("52", "GO"), Map.entry("53", "DF")
    );

    private IbgeUfLookup() {
    }

    /**
     * @param ibge6 6-digit IBGE code; only the first 2 chars are used.
     * @return the UF abbreviation (e.g., {@code "BA"}) or {@code null} when
     *         the prefix is not a known UF code.
     */
    static String ufFromIbge(String ibge6) {
        if (ibge6 == null || ibge6.length() < 2) {
            return null;
        }
        return CODE_TO_UF.get(ibge6.substring(0, 2));
    }
}
