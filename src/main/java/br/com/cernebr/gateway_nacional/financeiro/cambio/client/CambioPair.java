package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Representação interna de um par de moedas {@code XXX-YYY} parseado.
 *
 * <p>Os clients PTAX exigem que {@code moedaDestino} seja {@code BRL} (PTAX é
 * sempre fixing vs Real) e que {@code moedaOrigem} esteja no catálogo
 * {@link #PTAX_SUPPORTED} de moedas que o BCB efetivamente publica. Pares
 * fora desse contrato lançam {@link ResourceUnavailableException} e o
 * {@code CambioService} cascata para o AwesomeAPI.</p>
 *
 * <p>O catálogo embutido cobre as ~16 moedas conversíveis publicadas no PTAX
 * (verificado em maio/2026). Se o BCB acrescentar moedas, a chamada falhará
 * com {@code ResourceUnavailableException} indicando o gap — atualizar o
 * conjunto.</p>
 */
record CambioPair(String moedaOrigem, String moedaDestino) {

    /**
     * Catálogo de moedas que o BCB publica via PTAX. Mantido como conjunto
     * estático embutido para evitar uma chamada extra ao endpoint
     * {@code /CotacaoMoeda} apenas para validação — o trade-off é manutenção
     * manual quando o BCB acrescentar moedas (raro, ~uma por década).
     */
    private static final Set<String> PTAX_SUPPORTED = Set.of(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "DKK",
            "NOK", "SEK", "ARS", "MXN", "TRY", "ZAR", "CNY", "HKD"
    );

    static List<CambioPair> parseAll(String pares) {
        if (pares == null || pares.isBlank()) {
            throw new ResourceUnavailableException("PTAX",
                    "Lista de pares vazia.");
        }
        return Arrays.stream(pares.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(CambioPair::parse)
                .toList();
    }

    static CambioPair parse(String raw) {
        String upper = raw.toUpperCase(Locale.ROOT);
        String[] parts = upper.split("-");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ResourceUnavailableException("PTAX",
                    "Par '" + raw + "' não está no formato XXX-YYY exigido pelo PTAX.");
        }
        if (!"BRL".equals(parts[1])) {
            throw new ResourceUnavailableException("PTAX",
                    "Par '" + raw + "' não é PTAX-elegível: PTAX só publica cotações vs BRL.");
        }
        if (!PTAX_SUPPORTED.contains(parts[0])) {
            throw new ResourceUnavailableException("PTAX",
                    "Par '" + raw + "' não é PTAX-elegível: moeda " + parts[0] + " não consta no catálogo BCB.");
        }
        return new CambioPair(parts[0], parts[1]);
    }
}
