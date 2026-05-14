package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Faixa min/max de preço KBB para um canal específico de liquidação
 * (revenda profissional ou transação entre particulares).
 *
 * <p>O KBB publica os valores como uma banda: um mínimo realista para o pior
 * cenário do canal e um máximo para o melhor cenário (carro impecável,
 * documentação azul, mercado aquecido). O gateway preserva ambos para que o
 * consumidor decida onde plotar sua oferta dentro da faixa. Quando o KBB não
 * tem mapeamento para o veículo, o canal inteiro chega {@code null} —
 * {@code minimo == null && maximo == null} sinaliza indisponibilidade.</p>
 */
@Schema(name = "FaixaPrecoKbb",
        description = "Faixa min/max de preço KBB para um canal específico (Lojista ou Particular).")
public record FaixaPrecoKbb(
        @Schema(description = "Limite inferior da faixa publicada pelo KBB para o canal.",
                example = "38900.00")
        BigDecimal minimo,

        @Schema(description = "Limite superior da faixa publicada pelo KBB para o canal.",
                example = "44200.00")
        BigDecimal maximo
) {
}
