package br.com.cernebr.gateway_nacional.financeiro.b3.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Snapshot completo dos fundos B3 de um tipo específico (FII, FIAGRO-FII, etc.).
 * O tipo é parte da chave de cache — fundos de tipos diferentes têm entradas
 * Redis distintas. Mesmo motivo de wrapping do {@link B3StockTickersSnapshot}.
 */
@Schema(name = "B3FundosTickersSnapshot", hidden = true)
public record B3FundosTickersSnapshot(
        B3FundoTipo tipo,
        List<B3FundoTickerResponse> tickers,
        LocalDate dataReferencia
) {
}
