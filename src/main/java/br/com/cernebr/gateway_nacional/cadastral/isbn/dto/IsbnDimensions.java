package br.com.cernebr.gateway_nacional.cadastral.isbn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Dimensões físicas do livro. Unidade aderente à fonte que respondeu —
 * preserva o que o provider forneceu sem normalização forçada para evitar
 * arredondamento espúrio em conversão CM↔IN.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Dimensões físicas do livro")
public record IsbnDimensions(
        @Schema(example = "14.0") Double width,
        @Schema(example = "21.0") Double height,
        @Schema(example = "CENTIMETER", allowableValues = {"CENTIMETER", "INCH"}) String unit
) {
}
