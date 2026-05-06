package br.com.cernebr.gateway_nacional.rastreio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Single timeline entry of a tracking history. The combination of
 * {@code dataHora} + {@code local} is unique enough in practice to dedupe
 * events that show up across providers.
 */
@Schema(name = "EventoRastreio", description = "Evento individual da linha do tempo de rastreio.")
public record EventoRastreio(
        @Schema(description = "Data e hora do evento (ISO-8601 local)", example = "2025-04-21T10:30:00")
        LocalDateTime dataHora,

        @Schema(description = "Localidade onde o evento ocorreu", example = "CTE NORTE - Brasília/DF")
        String local,

        @Schema(description = "Status canônico do evento", example = "Postado")
        String status,

        @Schema(description = "Descrição detalhada do evento", example = "Objeto postado")
        String descricao
) {
}
