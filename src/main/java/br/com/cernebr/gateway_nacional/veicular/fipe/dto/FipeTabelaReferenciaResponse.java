package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tabela-de-referência FIPE — uma por mês de publicação. {@code codigo}
 * é o ID que identifica univocamente a tabela e pode ser passado em
 * {@code ?tabelaReferencia=N} nas consultas de marcas/veiculos para
 * recuperar listagens históricas.
 *
 * <p>Tabelas são listadas em ordem decrescente (mais recente primeiro) —
 * espelha o {@code .sort((a, b) => b.codigo - a.codigo)} da BrasilAPI.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Tabela-de-referência FIPE (uma por mês de publicação)")
public record FipeTabelaReferenciaResponse(
        @Schema(example = "333", description = "Código da tabela — usar em ?tabelaReferencia=N para histórico") int codigo,
        @Schema(example = "maio/2026") String mes
) {
}
