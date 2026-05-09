package br.com.cernebr.gateway_nacional.financeiro.cambio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Cotação de câmbio em formato unificado pelo Gateway Nacional.
 *
 * <p>{@code valorCompra} e {@code valorVenda} são {@link BigDecimal} de propósito —
 * sistemas financeiros e fiscais (cálculo de fretes, importações, NF-e) não toleram
 * a deriva de precisão de {@code double}.</p>
 */
@Schema(name = "CambioResponse",
        description = "Cotação atual de um par de moedas (origem → destino), unificada a partir da AwesomeAPI.")
public record CambioResponse(
        @Schema(description = "Código ISO da moeda de origem", example = "USD")
        String moedaOriginal,

        @Schema(description = "Código ISO da moeda de destino", example = "BRL")
        String moedaDestino,

        @Schema(description = "Cotação de compra (bid) — quanto a moeda de origem está sendo comprada na de destino", example = "5.2807")
        BigDecimal valorCompra,

        @Schema(description = "Cotação de venda (ask) — quanto a moeda de origem está sendo vendida na de destino", example = "5.2819")
        BigDecimal valorVenda,

        @Schema(description = "Variação percentual (%) frente ao fechamento anterior", example = "0.20")
        BigDecimal variacao,

        @Schema(description = "Data/hora local da última atualização da cotação", example = "2026-05-08T14:32:11", format = "date-time")
        LocalDateTime dataHoraAtualizacao
) {
}
