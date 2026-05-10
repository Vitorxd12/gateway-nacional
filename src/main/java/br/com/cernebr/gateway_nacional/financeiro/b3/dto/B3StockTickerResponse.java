package br.com.cernebr.gateway_nacional.financeiro.b3.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ticker de ação listada na B3. Shape em snake_case espelhado da BrasilAPI
 * (sufixos {@code _CVM}/{@code _BDR} mantidos em uppercase para paridade
 * com os identificadores oficiais).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Ticker de ação listada na B3")
public record B3StockTickerResponse(
        @JsonProperty("code_CVM")
        @Schema(name = "code_CVM", example = "01023") String codeCvm,
        @JsonProperty("issuing_company")
        @Schema(name = "issuing_company", example = "PETR") String issuingCompany,
        @JsonProperty("company_name")
        @Schema(name = "company_name", example = "PETROLEO BRASILEIRO S.A. PETROBRAS") String companyName,
        @JsonProperty("trading_name")
        @Schema(name = "trading_name", example = "PETROBRAS") String tradingName,
        @Schema(example = "33000167000101") String cnpj,
        @JsonProperty("market_indicator")
        @Schema(name = "market_indicator", example = "10") String marketIndicator,
        @JsonProperty("type_BDR")
        @Schema(name = "type_BDR") String typeBdr,
        @JsonProperty("date_listing")
        @Schema(name = "date_listing", example = "1968-04-22") String dateListing,
        @Schema(example = "LISTED") String status,
        @Schema(example = "Tradicional") String segment,
        @JsonProperty("segment_eng")
        @Schema(name = "segment_eng", example = "Traditional") String segmentEng,
        @Schema(example = "ACOES") String type,
        @Schema(example = "BOVESPA") String market
) {
}
