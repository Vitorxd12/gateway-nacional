package br.com.cernebr.gateway_nacional.financeiro.b3.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ticker de fundo listado na B3 ({@code FII}, {@code FIAGRO-FII}, {@code FIP}, etc.).
 * Shape mais enxuto que o de ações: a B3 publica menos metadados pra fundos.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Ticker de fundo listado na B3")
public record B3FundoTickerResponse(
        @Schema(example = "12345") Integer id,
        @JsonProperty("type_name")
        @Schema(name = "type_name", example = "Fundo de Investimento Imobiliário") String typeName,
        @Schema(example = "MXRF11") String acronym,
        @JsonProperty("fund_name")
        @Schema(name = "fund_name", example = "MAXI RENDA FII") String fundName,
        @JsonProperty("trading_name")
        @Schema(name = "trading_name", example = "MAXI RENDA") String tradingName
) {
}
