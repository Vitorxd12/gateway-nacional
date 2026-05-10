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
 * sempre fixing vs Real) e que {@code moedaOrigem} esteja no catálogo dinâmico
 * {@link BcbMoedasCatalogService} de moedas que o BCB efetivamente publica.
 * Pares fora desse contrato lançam {@link ResourceUnavailableException} e o
 * {@code CambioService} cascata para o AwesomeAPI.</p>
 *
 * <p>O catálogo é injetado por parâmetro (não é estático) para que cada client
 * controle qual fonte de verdade usa — habilita testes determinísticos e evita
 * acoplamento estático ao Spring container.</p>
 */
record CambioPair(String moedaOrigem, String moedaDestino) {

    static List<CambioPair> parseAll(String pares, Set<String> supportedOrigens) {
        if (pares == null || pares.isBlank()) {
            throw new ResourceUnavailableException("PTAX",
                    "Lista de pares vazia.");
        }
        return Arrays.stream(pares.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(raw -> parse(raw, supportedOrigens))
                .toList();
    }

    static CambioPair parse(String raw, Set<String> supportedOrigens) {
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
        if (!supportedOrigens.contains(parts[0])) {
            throw new ResourceUnavailableException("PTAX",
                    "Par '" + raw + "' não é PTAX-elegível: moeda " + parts[0] + " não consta no catálogo BCB.");
        }
        return new CambioPair(parts[0], parts[1]);
    }
}
