package br.com.cernebr.gateway_nacional.financeiro.taxas.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.taxas.dto.TaxaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Primary rate provider — BrasilAPI (https://brasilapi.com.br).
 * Endpoint: {@code /api/taxas/v1/{sigla}} returns a flat {@code {nome, valor}}
 * object. The endpoint does not expose a reference date, so the gateway
 * stamps the request with {@link LocalDate#now()} — semantically "today's
 * published value".
 */
@Slf4j
@Component
public class BrasilApiTaxasClient implements TaxaClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Taxas";

    private final RestClient restClient;

    public BrasilApiTaxasClient(RestClient.Builder builder,
                                @Value("${gateway.taxas.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiTaxasCB", fallbackMethod = "fallback")
    public TaxaResponse fetch(String sigla) {
        String slug = sigla.toLowerCase(Locale.ROOT);

        BrasilApiTaxaPayload payload = restClient.get()
                .uri("/api/taxas/v1/{sigla}", slug)
                .retrieve()
                .body(BrasilApiTaxaPayload.class);

        if (payload == null || payload.valor() == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou resposta vazia para a taxa solicitada.");
        }

        return new TaxaResponse(
                sigla.toUpperCase(Locale.ROOT),
                payload.valor(),
                LocalDate.now()
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private TaxaResponse fallback(String sigla, Throwable cause) {
        log.warn("BrasilAPI (Taxas) fallback triggered for sigla={} cause={}", sigla, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiTaxaPayload(String nome, BigDecimal valor) {
    }
}
