package br.com.cernebr.gateway_nacional.financeiro.cvm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope da listagem paginada de fundos. Mantém o shape da BrasilAPI
 * ({@code data, page, size}) mas adiciona {@code totalRecords} para cliente
 * pré-calcular última página sem chute.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Página da listagem de fundos CVM")
public record FundosPageResponse(
        @Schema(example = "100") int size,
        @Schema(example = "1") int page,
        @Schema(example = "29487", description = "Total de fundos no snapshot") int totalRecords,
        List<FundoSummaryResponse> data
) {
}
