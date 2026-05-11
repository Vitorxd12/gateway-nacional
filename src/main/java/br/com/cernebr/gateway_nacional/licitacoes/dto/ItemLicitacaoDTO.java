package br.com.cernebr.gateway_nacional.licitacoes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Item / lote de uma licitação. Modelagem mínima e suficiente para que
 * sistemas downstream consigam:
 *
 * <ul>
 *   <li>identificar o item ({@code numero}, {@code descricao});</li>
 *   <li>decidir participação (quantidade, unidade, valor de referência);</li>
 *   <li>classificar pelo catálogo do portal ({@code codigoCatalogo}).</li>
 * </ul>
 *
 * <p>Campos monetários são {@link BigDecimal} em BRL — nenhum portal publica
 * licitações em outra moeda. Quando o portal não publica preço estimado
 * (caso comum nas dispensas), {@code valorUnitarioEstimado} e
 * {@code valorTotalEstimado} ficam null e o consumidor sabe que precisa
 * abrir o edital para precificar.</p>
 */
@Schema(name = "ItemLicitacaoDTO",
        description = "Item ou lote individual dentro de uma licitação.")
public record ItemLicitacaoDTO(
        @Schema(description = "Número/ordem do item no edital.", example = "1")
        Integer numero,

        @Schema(description = "Descrição livre do item conforme publicado.",
                example = "Resma de papel A4, 75g, branco, certificação FSC, 500 folhas")
        String descricao,

        @Schema(description = "Quantidade prevista para contratação.", example = "1200")
        BigDecimal quantidade,

        @Schema(description = "Unidade de medida (UND, KG, M, RESMA, etc.).", example = "RESMA")
        String unidade,

        @Schema(description = "Código do catálogo do portal (CATSER/CATMAT no ComprasNet, código interno em outros).",
                example = "279100")
        String codigoCatalogo,

        @Schema(description = "Valor unitário estimado em BRL. null quando o portal não publica.",
                example = "28.50")
        BigDecimal valorUnitarioEstimado,

        @Schema(description = "Valor total estimado em BRL (quantidade × unitário). null quando estimativa não publicada.",
                example = "34200.00")
        BigDecimal valorTotalEstimado,

        @Schema(description = "Aplica benefício ME/EPP (Lei Complementar 123/2006).", example = "true")
        Boolean exclusivoMeEpp
) {
}
