package br.com.cernebr.gateway_nacional.financeiro.cambio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Entrada do catálogo dinâmico de moedas que o BCB publica via PTAX
 * ({@code /odata/Moedas}). Exposta pelo Gateway Nacional como contrato limpo
 * para clientes que querem listar quais pares são PTAX-elegíveis antes de
 * disparar uma cotação.
 *
 * <p>Campos mapeados a partir do payload OData oficial: {@code simbolo} é o
 * código ISO-4217 ({@code USD}, {@code EUR}, …) que o BCB aceita no parâmetro
 * {@code @moeda}; {@code nome} é a denominação formatada para exibição; e
 * {@code tipoMoeda} segue a classificação interna do BCB — {@code A}
 * (paridade contra USD) ou {@code B} (paridade direta contra BRL).</p>
 */
@Schema(name = "MoedaResponse",
        description = "Moeda do catálogo PTAX/BCB exposta pelo Gateway Nacional (símbolo ISO + nome + classe de paridade).")
public record MoedaResponse(
        @Schema(description = "Código ISO da moeda (PTAX-elegível quando consultada vs BRL)", example = "USD")
        String simbolo,

        @Schema(description = "Denominação formatada da moeda", example = "Dólar dos Estados Unidos")
        String nome,

        @Schema(description = "Classe de paridade BCB — A = paridade vs USD; B = paridade direta vs BRL", example = "A")
        String tipoMoeda
) {
}
