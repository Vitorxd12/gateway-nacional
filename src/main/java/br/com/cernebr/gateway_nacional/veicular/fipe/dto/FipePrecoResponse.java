package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Unified FIPE quote payload exposed by the Gateway, regardless of which
 * upstream provider (BrasilAPI, Parallelum) actually resolved the lookup.
 *
 * <p>{@code preco} is a {@link BigDecimal} on purpose — financial quotes
 * cannot tolerate {@code double} precision drift in credit simulations.
 * {@code anoModelo} uses the FIPE convention: 4-digit calendar year for
 * regular models and the special value {@code 32000} for "Zero KM".</p>
 */
@Schema(name = "FipePrecoResponse", description = "Cotação FIPE de um veículo em formato unificado pelo Gateway Nacional.")
public record FipePrecoResponse(
        @Schema(description = "Código FIPE no padrão 000000-0", example = "005340-0")
        String codigoFipe,

        @Schema(description = "Marca do veículo", example = "Volkswagen")
        String marca,

        @Schema(description = "Modelo (versão completa)", example = "Cross UP! TSI 1.0 12V Total Flex")
        String modelo,

        @Schema(description = "Ano modelo (32000 indica Zero KM, conforme convenção FIPE)", example = "2018")
        int anoModelo,

        @Schema(description = "Tipo de combustível", example = "Gasolina")
        String combustivel,

        @Schema(description = "Preço de referência em reais", example = "80444.00")
        BigDecimal preco,

        @Schema(description = "Mês de referência da cotação", example = "março de 2024")
        String mesReferencia
) {
}
