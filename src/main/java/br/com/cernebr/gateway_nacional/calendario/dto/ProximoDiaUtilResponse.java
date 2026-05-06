package br.com.cernebr.gateway_nacional.calendario.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Response payload for the "next business day" computation.
 */
@Schema(name = "ProximoDiaUtilResponse", description = "Resultado do cálculo do próximo dia útil a partir de uma data-base.")
public record ProximoDiaUtilResponse(
        @Schema(description = "Data informada na consulta", example = "2025-04-21", format = "date")
        LocalDate dataBase,

        @Schema(description = "Próximo dia útil resolvido (igual à dataBase quando ela já é um dia útil)", example = "2025-04-22", format = "date")
        LocalDate proximoDiaUtil,

        @Schema(description = "Quantidade de dias de calendário adicionados (zero quando dataBase já é dia útil)", example = "1")
        long diasAdicionados
) {
}
