package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Marca (montadora) listada na FIPE-Oficial. {@code valor} é o
 * {@code codigoMarca} usado em {@code GET /veiculos/{tipo}/{codigoMarca}}.
 *
 * <p>Shape espelhado da BrasilAPI ({@code services/fipe/automakers.js}):
 * {@code {nome, valor}} — ambos strings, valor numerico-encoded como string.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Marca/montadora listada na FIPE-Oficial")
public record FipeMarcaResponse(
        @Schema(example = "VW - VolksWagen") String nome,
        @Schema(example = "59", description = "Código da marca — usar como codigoMarca em /veiculos/{tipo}/{codigoMarca}") String valor
) {
}
