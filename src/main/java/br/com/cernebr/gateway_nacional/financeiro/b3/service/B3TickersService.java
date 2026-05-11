package br.com.cernebr.gateway_nacional.financeiro.b3.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.b3.client.B3TickersClient;
import br.com.cernebr.gateway_nacional.financeiro.b3.client.BrasilApiB3Client;
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
 * Resolve listagens da B3 priorizando a consulta direta ao sistema oficial.
 *
 * <h2>Cascata (ordem mandatória)</h2>
 * <ol>
 *   <li><b>Tier 1 — B3 oficial:</b> {@link B3TickersClient} faz a paginação
 *       paralela via base64-params no PATH. RAC mantém o snapshot em cache
 *       (hard 30d / soft 7d). Caminho normal e preferido.</li>
 *   <li><b>Tier 2 — BrasilAPI fallback:</b> {@link BrasilApiB3Client} acionado
 *       <em>somente</em> quando a B3 oficial está fora durante o refresh.
 *       BrasilAPI já agrega todas as páginas internamente — devolve a lista
 *       inteira numa chamada.</li>
 * </ol>
 *
 * <p><b>Diretriz mandatória:</b> a consulta direta à B3 nunca é pulada
 * em favor da BrasilAPI. Fallback existe pra indisponibilidade real do
 * sistema oficial durante o refresh do cache.</p>
 */
@Slf4j
@Service
public class B3TickersService {

    private static final String CACHE_ACOES = "b3Acoes";
    private static final String CACHE_FUNDOS = "b3Fundos";
    private static final String CACHE_KEY_ACOES = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(7);
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");

    private final B3TickersClient b3DirectClient;
    private final BrasilApiB3Client brasilApiFallbackClient;
    private final RefreshAheadCache refreshAheadCache;

    public B3TickersService(B3TickersClient b3DirectClient,
                            BrasilApiB3Client brasilApiFallbackClient,
                            RefreshAheadCache refreshAheadCache) {
        this.b3DirectClient = b3DirectClient;
        this.brasilApiFallbackClient = brasilApiFallbackClient;
        this.refreshAheadCache = refreshAheadCache;
    }

    public List<B3StockTickerResponse> listAcoes() {
        try {
            B3StockTickersSnapshot snapshot = refreshAheadCache.get(
                    CACHE_ACOES, CACHE_KEY_ACOES, SOFT_TTL,
                    () -> new B3StockTickersSnapshot(
                            b3DirectClient.fetchAllAcoes(),
                            LocalDate.now(BR_ZONE)));
            return snapshot.tickers();
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("B3 oficial (ações) indisponível ({}). Cascateando pra BrasilAPI fallback.",
                    tier1Failure.getMessage());
            try {
                return brasilApiFallbackClient.fetchAllAcoes();
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "listAcoes");
            }
        }
    }

    public List<B3FundoTickerResponse> listFundosByTipo(B3FundoTipo tipo) {
        try {
            B3FundosTickersSnapshot snapshot = refreshAheadCache.get(
                    CACHE_FUNDOS, tipo.wireValue(), SOFT_TTL,
                    () -> new B3FundosTickersSnapshot(
                            tipo,
                            b3DirectClient.fetchAllFundos(tipo),
                            LocalDate.now(BR_ZONE)));
            return snapshot.tickers();
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("B3 oficial (fundos {}) indisponível ({}). Cascateando pra BrasilAPI fallback.",
                    tipo, tier1Failure.getMessage());
            try {
                return brasilApiFallbackClient.fetchAllFundos(tipo);
            } catch (ResourceNotFoundException notFound) {
                throw notFound;
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "listFundos tipo=" + tipo);
            }
        }
    }

    private static ResourceUnavailableException unify(Throwable tier1, Throwable tier2, String context) {
        ResourceUnavailableException unified = new ResourceUnavailableException("b3",
                "B3 oficial e BrasilAPI (fallback) falharam para " + context, tier2);
        unified.addSuppressed(tier1);
        return unified;
    }
}
