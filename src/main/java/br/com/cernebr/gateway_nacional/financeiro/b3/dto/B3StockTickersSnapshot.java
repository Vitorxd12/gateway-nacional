package br.com.cernebr.gateway_nacional.financeiro.b3.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Snapshot completo de todas as ações listadas na B3, cacheado inteiro no
 * Redis. Wrapping de {@code List<>} é obrigatório para entrar no
 * {@code RAC} (gap do default-typing tratado pelo
 * {@code ResilientGenericJacksonSerializer}).
 */
@Schema(name = "B3StockTickersSnapshot", hidden = true)
public record B3StockTickersSnapshot(
        List<B3StockTickerResponse> tickers,
        LocalDate dataReferencia
) {
}
