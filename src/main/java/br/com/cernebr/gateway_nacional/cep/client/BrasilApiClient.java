package br.com.cernebr.gateway_nacional.cep.client;

import br.com.cernebr.gateway_nacional.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Secondary CEP provider — BrasilAPI (https://brasilapi.com.br).
 * v1 endpoint does not expose 'complemento' nor IBGE code.
 */
@Slf4j
@Component("cepBrasilApiClient")
public class BrasilApiClient implements CepClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI";
    private static final String BASE_URL = "https://brasilapi.com.br";

    private final RestClient restClient;

    public BrasilApiClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiCB", fallbackMethod = "fallback")
    public CepResponse fetch(String cep) {
        BrasilApiPayload payload = restClient.get()
                .uri("/api/cep/v1/{cep}", cep)
                .retrieve()
                .body(BrasilApiPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou resposta vazia ou CEP não localizado.");
        }
        return payload.toCepResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CepResponse fallback(String cep, Throwable cause) {
        log.warn("BrasilAPI fallback triggered for cep={} cause={}", cep, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiPayload(
            String cep,
            String state,
            String city,
            String neighborhood,
            String street,
            String service
    ) {
        boolean isInvalid() {
            return cep == null || cep.isBlank();
        }

        CepResponse toCepResponse() {
            return new CepResponse(cep, street, null, neighborhood, city, state, null);
        }
    }
}
