package br.com.cernebr.gateway_nacional.financeiro.taxas.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Unified rate payload exposed by the Gateway, regardless of which upstream
 * provider (BrasilAPI, BCB SGS, HG Brasil) actually resolved the lookup.
 *
 * <p>{@code valor} is a {@link BigDecimal} on purpose — financial systems
 * cannot tolerate {@code double} precision drift in interest accruals.</p>
 */
@Schema(name = "TaxaResponse", description = "Cotação de índice financeiro brasileiro (CDI, Selic, IPCA) em formato unificado pelo Gateway Nacional.")
public record TaxaResponse(
        @Schema(description = "Sigla canônica do índice em maiúsculas", example = "CDI")
        String nome,

        @Schema(description = "Valor da taxa publicado pela última leitura. Para Selic/CDI representa percentual ao ano; para IPCA representa percentual mensal.", example = "10.65")
        BigDecimal valor,

        @Schema(description = "Data de referência da última leitura disponível", example = "2025-04-21", format = "date")
        LocalDate dataReferencia
) {
}
