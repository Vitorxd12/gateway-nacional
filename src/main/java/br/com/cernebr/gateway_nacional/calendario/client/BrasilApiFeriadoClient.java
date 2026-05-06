package br.com.cernebr.gateway_nacional.calendario.client;

import br.com.cernebr.gateway_nacional.calendario.dto.FeriadoResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Primary holiday provider — BrasilAPI (https://brasilapi.com.br).
 * Endpoint returns an array of {@code {date, name, type}} objects.
 */
@Slf4j
@Component
public class BrasilApiFeriadoClient implements FeriadoClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Feriados";

    private static final String DEFAULT_TIPO = "Nacional";
    private static final ParameterizedTypeReference<List<BrasilApiFeriadoPayload>> PAYLOAD_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiFeriadoClient(RestClient.Builder builder,
                                  @Value("${gateway.calendario.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiFeriadosCB", fallbackMethod = "fallback")
    public List<FeriadoResponse> fetch(int ano) {
        List<BrasilApiFeriadoPayload> payload = restClient.get()
                .uri("/api/feriados/v1/{ano}", ano)
                .retrieve()
                .body(PAYLOAD_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia de feriados para o ano solicitado.");
        }
        return payload.stream().map(BrasilApiFeriadoPayload::toFeriadoResponse).toList();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<FeriadoResponse> fallback(int ano, Throwable cause) {
        log.warn("BrasilAPI (Feriados) fallback triggered for ano={} cause={}", ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiFeriadoPayload(LocalDate date, String name, String type) {
        FeriadoResponse toFeriadoResponse() {
            return new FeriadoResponse(date, name, DEFAULT_TIPO);
        }
    }
}
