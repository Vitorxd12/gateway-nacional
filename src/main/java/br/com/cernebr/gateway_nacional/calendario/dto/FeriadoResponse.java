package br.com.cernebr.gateway_nacional.calendario.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Unified holiday payload exposed by the Gateway, regardless of which upstream
 * (BrasilAPI, Nager.Date, in-memory calculator) actually produced the data.
 */
@Schema(name = "FeriadoResponse", description = "Feriado nacional brasileiro em formato unificado pelo Gateway Nacional.")
public record FeriadoResponse(
        @Schema(description = "Data do feriado", example = "2025-04-21", format = "date")
        LocalDate data,

        @Schema(description = "Nome oficial do feriado", example = "Tiradentes")
        String nome,

        @Schema(description = "Classificação do feriado", example = "Nacional")
        String tipo
) {
}
