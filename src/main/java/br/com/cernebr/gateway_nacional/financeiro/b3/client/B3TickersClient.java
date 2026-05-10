package br.com.cernebr.gateway_nacional.financeiro.b3.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTickerResponse;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTipo;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3StockTickerResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cliente único da B3 para listagem de tickers (ações + fundos).
 *
 * <h2>Convenção esquisita do upstream</h2>
 * <p>A B3 não usa query string — os parâmetros vão como <b>JSON serializado
 * em base64 dentro do PATH</b>:</p>
 * <pre>
 * /listedCompaniesProxy/CompanyCall/GetInitialCompanies/{base64(JSON)}
 * /fundsListedProxy/Search/GetListFunds/{base64(JSON)}
 * </pre>
 * <p>Onde {@code JSON} é {@code {"language":"pt-br","pageNumber":1,"pageSize":120,"typeFund":"FII"}}
 * (typeFund só pra fundos). Replicamos exatamente o que a BrasilAPI faz em
 * {@code services/tickers/listTickers.js} — invenção própria daria divergência
 * silenciosa com o upstream.</p>
 *
 * <h2>Paginação obrigatória</h2>
 * <p>Não dá pra pedir tudo de uma vez. Estratégia:</p>
 * <ol>
 *   <li>1ª chamada com {@code pageNumber=1} para descobrir {@code totalRecords}</li>
 *   <li>Calcula {@code totalPages = ceil(totalRecords / PAGE_SIZE)}</li>
 *   <li>Dispara páginas 2..N <b>em paralelo</b> via virtual threads</li>
 *   <li>Concatena resultados na ordem das páginas</li>
 * </ol>
 *
 * <p>Para ações (~600 listadas), são ~5 páginas em paralelo após a sonda.
 * Para FII (~300), ~3 páginas. Wall-time tracks {@code max(latência por página)},
 * não a soma.</p>
 */
@Slf4j
@Component
public class B3TickersClient {

    public static final String PROVIDER_NAME = "B3";

    private static final String STOCKS_PATH =
            "/listedCompaniesProxy/CompanyCall/GetInitialCompanies/{params}";
    private static final String FUNDS_PATH =
            "/fundsListedProxy/Search/GetListFunds/{params}";

    /**
     * Tamanho de página da B3 — replicado da BrasilAPI. 120 maximiza a
     * resposta sem disparar payload muito grande.
     */
    private static final int PAGE_SIZE = 120;
    private static final String LANGUAGE = "pt-br";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService pageExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public B3TickersClient(RestClient.Builder builder,
                           @Value("${gateway.b3.base-url:https://sistemaswebb3-listados.b3.com.br}") String baseUrl,
                           ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "b3TickersCB", fallbackMethod = "fallbackStocks")
    public List<B3StockTickerResponse> fetchAllAcoes() {
        StockPage probe = fetchStockPage(1);
        List<B3StockTickerResponse> all = new ArrayList<>(probe.totalRecords);
        all.addAll(probe.tickers);

        int totalPages = (int) Math.ceil((double) probe.totalRecords / PAGE_SIZE);
        if (totalPages <= 1) {
            return all;
        }

        List<CompletableFuture<StockPage>> futures = new ArrayList<>(totalPages - 1);
        for (int p = 2; p <= totalPages; p++) {
            int pageNumber = p;
            futures.add(CompletableFuture.supplyAsync(() -> fetchStockPage(pageNumber), pageExecutor));
        }
        for (CompletableFuture<StockPage> future : futures) {
            all.addAll(future.join().tickers);
        }
        return all;
    }

    @CircuitBreaker(name = "b3TickersCB", fallbackMethod = "fallbackFundos")
    public List<B3FundoTickerResponse> fetchAllFundos(B3FundoTipo tipo) {
        FundoPage probe = fetchFundoPage(tipo, 1);
        List<B3FundoTickerResponse> all = new ArrayList<>(probe.totalRecords);
        all.addAll(probe.tickers);

        int totalPages = (int) Math.ceil((double) probe.totalRecords / PAGE_SIZE);
        if (totalPages <= 1) {
            return all;
        }

        List<CompletableFuture<FundoPage>> futures = new ArrayList<>(totalPages - 1);
        for (int p = 2; p <= totalPages; p++) {
            int pageNumber = p;
            futures.add(CompletableFuture.supplyAsync(() -> fetchFundoPage(tipo, pageNumber), pageExecutor));
        }
        for (CompletableFuture<FundoPage> future : futures) {
            all.addAll(future.join().tickers);
        }
        return all;
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<B3StockTickerResponse> fallbackStocks(Throwable cause) {
        log.warn("B3 stocks fallback triggered: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "B3 ações indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private List<B3FundoTickerResponse> fallbackFundos(B3FundoTipo tipo, Throwable cause) {
        log.warn("B3 fundos fallback triggered for tipo={} cause={}", tipo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "B3 fundos (" + tipo + ") indisponível ou Circuit Breaker aberto.", cause);
    }

    private StockPage fetchStockPage(int pageNumber) {
        String paramsB64 = encodeParams(Map.of(
                "language", LANGUAGE,
                "pageNumber", pageNumber,
                "pageSize", PAGE_SIZE
        ));

        StockPagePayload payload = restClient.get()
                .uri(STOCKS_PATH, paramsB64)
                .retrieve()
                .body(StockPagePayload.class);

        if (payload == null || payload.page() == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "B3 stocks page=" + pageNumber + " devolveu corpo inesperado.");
        }
        return new StockPage(
                payload.page().totalRecords() == null ? 0 : payload.page().totalRecords(),
                payload.results() == null ? List.of() : payload.results().stream()
                        .map(StockPagePayload.Item::toUnified).toList()
        );
    }

    private FundoPage fetchFundoPage(B3FundoTipo tipo, int pageNumber) {
        // LinkedHashMap preserva ordem de inserção — o JSON resultante fica
        // estável ({language, pageNumber, pageSize, typeFund}), o que importa
        // se a B3 usar o JSON literal pra cache-key interno.
        Map<String, Object> params = new LinkedHashMap<>(4);
        params.put("language", LANGUAGE);
        params.put("pageNumber", pageNumber);
        params.put("pageSize", PAGE_SIZE);
        params.put("typeFund", tipo.wireValue());
        String paramsB64 = encodeParams(params);

        FundoPagePayload payload = restClient.get()
                .uri(FUNDS_PATH, paramsB64)
                .retrieve()
                .body(FundoPagePayload.class);

        if (payload == null || payload.page() == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "B3 fundos page=" + pageNumber + " tipo=" + tipo + " devolveu corpo inesperado.");
        }
        return new FundoPage(
                payload.page().totalRecords() == null ? 0 : payload.page().totalRecords(),
                payload.results() == null ? List.of() : payload.results().stream()
                        .map(FundoPagePayload.Item::toUnified).toList()
        );
    }

    private String encodeParams(Map<String, Object> params) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(params);
            return Base64.getEncoder().encodeToString(json);
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao serializar params B3 em JSON: " + ex.getMessage(), ex);
        }
    }

    private record StockPage(int totalRecords, List<B3StockTickerResponse> tickers) {
    }

    private record FundoPage(int totalRecords, List<B3FundoTickerResponse> tickers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StockPagePayload(PageInfo page, List<Item> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(
                String codeCVM, String issuingCompany, String companyName, String tradingName,
                String cnpj, String marketIndicator, String typeBDR, String dateListing,
                String status, String segment, String segmentEng, String type, String market
        ) {
            B3StockTickerResponse toUnified() {
                return new B3StockTickerResponse(
                        codeCVM, issuingCompany, companyName, tradingName, cnpj,
                        marketIndicator, typeBDR, dateListing, status,
                        segment, segmentEng, type, market
                );
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FundoPagePayload(PageInfo page, List<Item> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(Integer id, String typeName, String acronym,
                    String fundName, String tradingName) {
            B3FundoTickerResponse toUnified() {
                return new B3FundoTickerResponse(id, typeName, acronym, fundName, tradingName);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PageInfo(Integer pageNumber, Integer pageSize,
                            Integer totalPages, Integer totalRecords) {
    }
}
