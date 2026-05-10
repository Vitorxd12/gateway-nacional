package br.com.cernebr.gateway_nacional.cadastral.isbn.client;

import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnDimensions;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnRetailPrice;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Provider PRIMÁRIO de ISBN — BrasilAPI ({@code https://brasilapi.com.br/api/isbn/v1/{isbn}}).
 *
 * <p>Estratégia de paridade definida pela RULE B: a própria API pública da
 * BrasilAPI é o nosso provider principal porque ela já consolida internamente
 * os 4 fallbacks que mantemos isolados aqui (CBL, Google Books, Mercado
 * Editorial, Open Library). Em hedge paralelo, esperamos que a BrasilAPI
 * normalmente vença pela cadeia já otimizada lá; os 4 originais ficam como
 * rede de proteção quando a BrasilAPI estiver indisponível.</p>
 */
@Slf4j
@Component
public class BrasilApiIsbnClient implements IsbnClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI";

    private final RestClient restClient;

    public BrasilApiIsbnClient(RestClient.Builder builder,
                               @Value("${gateway.isbn.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "isbnBrasilApiCB", fallbackMethod = "fallback")
    public IsbnResponse fetch(String isbn) {
        BrasilApiIsbnPayload payload = restClient.get()
                .uri("/api/isbn/v1/{isbn}", isbn)
                .retrieve()
                .body(BrasilApiIsbnPayload.class);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou corpo vazio ou ISBN não localizado.");
        }
        return payload.toResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private IsbnResponse fallback(String isbn, Throwable cause) {
        log.warn("BrasilAPI fallback triggered for isbn={} cause={}", isbn, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiIsbnPayload(
            String isbn,
            String title,
            String subtitle,
            List<String> authors,
            String publisher,
            String synopsis,
            BrasilApiDimensions dimensions,
            Integer year,
            String format,
            Integer page_count,
            List<String> subjects,
            String location,
            BrasilApiPrice retail_price,
            String cover_url
    ) {
        boolean isEmpty() {
            return (isbn == null || isbn.isBlank()) && (title == null || title.isBlank());
        }

        IsbnResponse toResponse() {
            IsbnDimensions dim = dimensions == null ? null
                    : new IsbnDimensions(dimensions.width(), dimensions.height(), dimensions.unit());
            IsbnRetailPrice price = retail_price == null ? null
                    : new IsbnRetailPrice(retail_price.currency(), retail_price.amount());
            return new IsbnResponse(
                    isbn, title, subtitle, authors, publisher, synopsis,
                    dim, year, format, page_count, subjects, location,
                    price, cover_url, PROVIDER_NAME
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiDimensions(Double width, Double height, String unit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiPrice(String currency, BigDecimal amount) {
    }
}
