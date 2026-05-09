package br.com.cernebr.gateway_nacional.cadastral.cep.client;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Tertiary CEP provider — AwesomeAPI (https://cep.awesomeapi.com.br).
 * Field names diverge from the others (code/address/district/city_ibge).
 */
@Slf4j
@Component
public class AwesomeApiClient implements CepClientProvider {

    public static final String PROVIDER_NAME = "AwesomeAPI";

    private final RestClient restClient;

    public AwesomeApiClient(RestClient.Builder builder,
                            @Value("${gateway.cep.awesomeapi.base-url:https://cep.awesomeapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "awesomeApiCB", fallbackMethod = "fallback")
    public CepResponse fetch(String cep) {
        AwesomeApiPayload payload = restClient.get()
                .uri("/json/{cep}", cep)
                .retrieve()
                .body(AwesomeApiPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "AwesomeAPI retornou resposta vazia ou CEP não localizado.");
        }
        return payload.toCepResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CepResponse fallback(String cep, Throwable cause) {
        log.warn("AwesomeAPI fallback triggered for cep={} cause={}", cep, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "AwesomeAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AwesomeApiPayload(
            String code,
            String state,
            String city,
            String district,
            String address,
            @JsonProperty("city_ibge") String cityIbge
    ) {
        boolean isInvalid() {
            return code == null || code.isBlank();
        }

        CepResponse toCepResponse() {
            return new CepResponse(code, address, null, district, city, state, cityIbge);
        }
    }
}
