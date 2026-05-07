package br.com.cernebr.gateway_nacional.placa.client;

/**
 * Privacy utility — masks a vehicle chassis to its last 4 characters
 * preceded by three asterisks. Centralized here so every client applies
 * the same transformation; the un-masked chassi never crosses the gateway
 * boundary (logs, cache, response, or otherwise).
 */
final class ChassiMask {

    private ChassiMask() {
    }

    /**
     * Returns {@code "***" + last4(chassi)} or {@code null} when input is
     * blank. For inputs shorter than 4 characters, returns
     * {@code "***" + chassi} as-is — degenerate but safe.
     */
    static String mask(String chassi) {
        if (chassi == null || chassi.isBlank()) {
            return null;
        }
        String trimmed = chassi.trim();
        if (trimmed.length() <= 4) {
            return "***" + trimmed;
        }
        return "***" + trimmed.substring(trimmed.length() - 4);
    }
}
