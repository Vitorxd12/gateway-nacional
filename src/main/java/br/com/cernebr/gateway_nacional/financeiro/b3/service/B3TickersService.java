package br.com.cernebr.gateway_nacional.financeiro.b3.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.financeiro.b3.client.B3TickersClient;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTickerResponse;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTipo;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundosTickersSnapshot;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3StockTickerResponse;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3StockTickersSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Resolve listagens da B3 sobre snapshots cacheados. Single-provider de
 * propósito: a B3 é a fonte canônica das listagens — qualquer outro provider
 * seria um espelho. Hedge não se aplica.
 *
 * <p>Listagens são pesadas (5+ requests paralelas para descobrir e baixar
 * todas as páginas). RAC com hard-TTL 30d / soft 7d garante que esse trabalho
 * só acontece ~uma vez por semana — composição "RAC ⊃ paginação paralela
 * interna" mantém a primeira leitura aceitável (~3-5s) e todas as seguintes
 * instantâneas.</p>
 */
@Slf4j
@Service
public class B3TickersService {

    private static final String CACHE_ACOES = "b3Acoes";
    private static final String CACHE_FUNDOS = "b3Fundos";
    private static final String CACHE_KEY_ACOES = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(7);
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");

    private final B3TickersClient client;
    private final RefreshAheadCache refreshAheadCache;

    public B3TickersService(B3TickersClient client, RefreshAheadCache refreshAheadCache) {
        this.client = client;
        this.refreshAheadCache = refreshAheadCache;
    }

    public List<B3StockTickerResponse> listAcoes() {
        B3StockTickersSnapshot snapshot = refreshAheadCache.get(
                CACHE_ACOES, CACHE_KEY_ACOES, SOFT_TTL,
                () -> new B3StockTickersSnapshot(
                        client.fetchAllAcoes(),
                        LocalDate.now(BR_ZONE)));
        return snapshot.tickers();
    }

    public List<B3FundoTickerResponse> listFundosByTipo(B3FundoTipo tipo) {
        // Cache key inclui o wireValue do tipo — fundos de tipos diferentes
        // têm entradas Redis distintas (e timeouts independentes).
        B3FundosTickersSnapshot snapshot = refreshAheadCache.get(
                CACHE_FUNDOS, tipo.wireValue(), SOFT_TTL,
                () -> new B3FundosTickersSnapshot(
                        tipo,
                        client.fetchAllFundos(tipo),
                        LocalDate.now(BR_ZONE)));
        return snapshot.tickers();
    }
}
