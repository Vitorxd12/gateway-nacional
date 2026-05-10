package br.com.cernebr.gateway_nacional.cadastral.isbn.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validação de ISBN-10/13 (algoritmo de checksum) e conversão entre os
 * dois formatos.
 *
 * <p>Padrão {@link #ISBN_PATTERN} aceita somente formato puro — sem hífens
 * ou espaços. A {@link #normalize(String) normalização} fica a cargo do
 * caller (controller normaliza antes da validação para deixar o utilitário
 * agnóstico de input cosmético).</p>
 *
 * <p>Suporte a ISBN global (não restrito a códigos brasileiros): a BrasilAPI
 * historicamente filtra por prefixos 65/85, mas a base instalada do gateway
 * inclui usuários consultando títulos importados — Google Books e Open
 * Library têm cobertura mundial e se beneficiam de aceitar ISBNs estrangeiros.
 * O hedge degrada graciosamente: providers BR-only (CBL, Mercado Editorial)
 * falham para ISBN não-BR e os outros vencem.</p>
 */
public final class IsbnValidator {

    private static final Pattern ISBN_PATTERN = Pattern.compile("^\\d{13}$|^\\d{9}[\\dX]$");

    private IsbnValidator() {
    }

    /**
     * Remove hífens e espaços, força uppercase para que o {@code 'X'}
     * terminal de ISBN-10 entre canônico no checksum.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String normalizedIsbn) {
        if (normalizedIsbn == null || !ISBN_PATTERN.matcher(normalizedIsbn).matches()) {
            return false;
        }
        return normalizedIsbn.length() == 10
                ? isValidIsbn10(normalizedIsbn)
                : isValidIsbn13(normalizedIsbn);
    }

    private static boolean isValidIsbn10(String isbn) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = isbn.charAt(i);
            int digit = (c == 'X') ? 10 : (c - '0');
            sum += (10 - i) * digit;
        }
        return sum % 11 == 0;
    }

    private static boolean isValidIsbn13(String isbn) {
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            int digit = isbn.charAt(i) - '0';
            sum += ((i + 1) % 2 == 0 ? 3 : 1) * digit;
        }
        return sum % 10 == 0;
    }

    /**
     * Converte ISBN-10 → ISBN-13 prefixando {@code 978} e recalculando o
     * dígito verificador. Retorna o input se já for ISBN-13.
     *
     * @throws IllegalArgumentException se o input não for um ISBN válido
     */
    public static String toIsbn13(String normalizedIsbn) {
        requireValid(normalizedIsbn);
        if (normalizedIsbn.length() == 13) {
            return normalizedIsbn;
        }
        String prefix12 = "978" + normalizedIsbn.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = prefix12.charAt(i) - '0';
            sum += ((i + 1) % 2 == 0 ? 3 : 1) * digit;
        }
        int last = sum % 10;
        int checkDigit = (last == 0) ? 0 : 10 - last;
        return prefix12 + checkDigit;
    }

    /**
     * Converte ISBN-13 → ISBN-10 removendo o prefixo {@code 978} e
     * recalculando. Retorna o input se já for ISBN-10. Lança se o
     * prefixo for {@code 979} (não há ISBN-10 equivalente — só os
     * antigos {@code 978} retro-mapeiam).
     */
    public static String toIsbn10(String normalizedIsbn) {
        requireValid(normalizedIsbn);
        if (normalizedIsbn.length() == 10) {
            return normalizedIsbn;
        }
        if (!normalizedIsbn.startsWith("978")) {
            throw new IllegalArgumentException(
                    "ISBN-13 com prefixo 979 não tem equivalente ISBN-10.");
        }
        String body9 = normalizedIsbn.substring(3, 12);
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = body9.charAt(i) - '0';
            sum += digit * (i + 1);
        }
        int last = sum % 11;
        char checkDigit = (last == 10) ? 'X' : (char) ('0' + last);
        return body9 + checkDigit;
    }

    private static void requireValid(String isbn) {
        if (!isValid(isbn)) {
            throw new IllegalArgumentException("ISBN inválido: " + isbn);
        }
    }
}
