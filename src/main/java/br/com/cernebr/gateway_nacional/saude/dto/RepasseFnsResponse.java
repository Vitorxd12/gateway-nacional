package br.com.cernebr.gateway_nacional.saude.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One repasse line as published by the FNS (Fundo Nacional de Saúde) detalhada
 * consultation, normalized to the gateway's contract.
 *
 * <p>{@code bloco} carries the upstream classification ({@code grupoAcao.nome}
 * — e.g., {@code "ATENCAO PRIMARIA"}, {@code "VIGILANCIA EM SAUDE"}) when
 * available; falls back to {@code descricao} for repasses that arrive without
 * the parent group. {@code valorTotal} mirrors {@code valorLiquido} from the
 * upstream payload as {@link BigDecimal} — financial precision, no
 * floating-point drift in audit reconciliation.</p>
 */
@Schema(name = "RepasseFnsResponse",
        description = "Linha de repasse financeiro do FNS unificada pelo Gateway Nacional.")
public record RepasseFnsResponse(
        @Schema(description = "Código IBGE do município (6 dígitos, padrão SUS)", example = "292270")
        String codigoIbge,

        @Schema(description = "Competência do repasse no formato yyyy-MM", example = "2024-02")
        String competencia,

        @Schema(description = "Bloco / grupo de ação do repasse (do upstream `grupoAcao.nome` ou, em fallback, `descricao`)",
                example = "ATENCAO PRIMARIA")
        String bloco,

        @Schema(description = "Valor líquido do repasse em reais", example = "125430.50")
        BigDecimal valorTotal
) {
}
