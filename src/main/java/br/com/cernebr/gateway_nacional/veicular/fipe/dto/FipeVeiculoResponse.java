package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Modelo de veículo listado pela FIPE-Oficial para uma marca. {@code modelo}
 * é o nome canônico, {@code valor} é o ID interno do modelo no banco FIPE.
 *
 * <p><b>Degradação no fallback:</b> a BrasilAPI proxia este endpoint mas
 * intencionalmente descarta o {@code Value} ({@code services/fipe/vehiclesByMakers.js}
 * só retorna {@code modelo}). Quando o cascade cai pra BrasilAPI, {@code valor}
 * vem {@code null} e é omitido do JSON via {@link JsonInclude#NON_NULL}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Modelo de veículo listado pela FIPE-Oficial")
public record FipeVeiculoResponse(
        @Schema(example = "Gol 1.0 Total Flex 4p") String modelo,
        @Schema(example = "8888", description = "ID interno do modelo na base FIPE — null quando resolvido via fallback BrasilAPI") String valor
) {
}
