package br.com.cernebr.gateway_nacional.financeiro.b3.client;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTickerResponse;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTipo;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3StockTickerResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Provider FALLBACK de B3 — BrasilAPI ({@code /api/tickers/b3/...}).
 *
 * <p><b>Posição na cascata:</b> {@link B3TickersClient} (consulta direta à
 * B3 via base64-params + paginação paralela) é o tier 1. Este cliente entra
 * apenas quando o sistema da B3 está fora durante o refresh do snapshot.</p>
 *
 * <p>BrasilAPI já entrega a lista completa em snake_case — shape compatível
 * 1:1 com {@link B3StockTickerResponse} e {@link B3FundoTickerResponse}.
 * Não precisa de paginação aqui: a BrasilAPI internamente já faz o trabalho
 * de agregar todas as páginas e devolve o array consolidado.</p>
 */
@Slf4j
@Component
public class BrasilApiB3Client {

    public static final String PROVIDER_NAME = "BrasilAPI-B3";

    private static final ParameterizedTypeReference<List<B3StockTickerResponse>> STOCKS_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<B3FundoTickerResponse>> FUNDOS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiB3Client(RestClient.Builder builder,
                             @Value("${gateway.b3.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "brasilApiB3CB", fallbackMethod = "fallbackAcoes")
    public List<B3StockTickerResponse> fetchAllAcoes() {
        log.debug("BrasilAPI B3 ações fetch (fallback)");
        List<B3StockTickerResponse> raw = restClient.get()
                .uri("/api/tickers/b3/acoes/v1")
                .retrieve()
                .body(STOCKS_TYPE);

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI B3 ações retornou lista vazia.");
        }
        return raw;
    }

    @CircuitBreaker(name = "brasilApiB3CB", fallbackMethod = "fallbackFundos")
    public List<B3FundoTickerResponse> fetchAllFundos(B3FundoTipo tipo) {
        log.debug("BrasilAPI B3 fundos fetch tipo={} (fallback)", tipo);
        try {
            List<B3FundoTickerResponse> raw = restClient.get()
                    .uri("/api/tickers/b3/fundos/v1/{tipo}", tipo.wireValue())
                    .retrieve()
                    .body(FUNDOS_TYPE);

            if (raw == null || raw.isEmpty()) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "BrasilAPI B3 fundos retornou lista vazia para tipo=" + tipo);
            }
            return raw;
        } catch (HttpClientErrorException.NotFound nf) {
            throw new ResourceNotFoundException("B3FundoTipo",
                    "Tipo de fundo " + tipo + " não localizado pela BrasilAPI.");
        }
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<B3StockTickerResponse> fallbackAcoes(Throwable cause) {
        log.warn("BrasilAPI B3 ações fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI B3 indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private List<B3FundoTickerResponse> fallbackFundos(B3FundoTipo tipo, Throwable cause) {
        if (cause instanceof ResourceNotFoundException rnf) throw rnf;
        log.warn("BrasilAPI B3 fundos fallback tipo={} cause={}", tipo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI B3 indisponível ou Circuit Breaker aberto.", cause);
    }
}
