package br.com.cernebr.gateway_nacional.operacional.calendario.client;

import br.com.cernebr.gateway_nacional.operacional.calendario.dto.FeriadoResponse;
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
 *
 * <p>Two endpoints are exercised depending on input scope:</p>
 * <ul>
 *   <li>{@code /api/feriados/v1/{ano}} — national holidays only.</li>
 *   <li>{@code /api/feriados/v1/{ano}/{siglaUf}} — national + state holidays.</li>
 * </ul>
 */
@Slf4j
@Component
public class BrasilApiFeriadoClient implements FeriadoClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Feriados";

    private static final String TIPO_NACIONAL = "Nacional";
    private static final String TIPO_ESTADUAL = "Estadual";
    private static final ParameterizedTypeReference<List<BrasilApiFeriadoPayload>> PAYLOAD_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiFeriadoClient(RestClient.Builder builder,
                                  @Value("${gateway.calendario.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiFeriadosCB", fallbackMethod = "fallback")
    public List<FeriadoResponse> fetch(int ano, String siglaUf) {
        boolean stateScoped = siglaUf != null && !siglaUf.isBlank();

        List<BrasilApiFeriadoPayload> payload = stateScoped
                ? restClient.get()
                    .uri("/api/feriados/v1/{ano}/{uf}", ano, siglaUf)
                    .retrieve()
                    .body(PAYLOAD_TYPE)
                : restClient.get()
                    .uri("/api/feriados/v1/{ano}", ano)
                    .retrieve()
                    .body(PAYLOAD_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia de feriados para o ano solicitado.");
        }
        return payload.stream()
                .map(item -> item.toFeriadoResponse(stateScoped))
                .toList();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<FeriadoResponse> fallback(int ano, String siglaUf, Throwable cause) {
        log.warn("BrasilAPI (Feriados) fallback triggered for ano={} siglaUf={} cause={}",
                ano, siglaUf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiFeriadoPayload(LocalDate date, String name, String type) {
        FeriadoResponse toFeriadoResponse(boolean stateScopedRequest) {
            // BrasilAPI returns "national" or "state" in the type field. We surface a
            // friendly Portuguese label; if absent, fall back based on the request scope.
            String tipo;
            if ("state".equalsIgnoreCase(type)) {
                tipo = TIPO_ESTADUAL;
            } else if ("national".equalsIgnoreCase(type)) {
                tipo = TIPO_NACIONAL;
            } else {
                tipo = stateScopedRequest ? TIPO_ESTADUAL : TIPO_NACIONAL;
            }
            return new FeriadoResponse(date, name, tipo);
        }
    }
}
