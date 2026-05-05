package br.com.cernebr.gateway_nacional.cep.client;

import br.com.cernebr.gateway_nacional.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Primary CEP provider — ViaCEP (https://viacep.com.br).
 *
 * <p>ViaCEP returns HTTP 200 with {@code {"erro": true}} (and missing address
 * fields) when the CEP is not found. We treat such responses as a provider
 * failure so the orchestrator can cascade to the next provider.</p>
 */
@Slf4j
@Component
public class ViaCepClient implements CepClientProvider {

    public static final String PROVIDER_NAME = "ViaCEP";
    private static final String BASE_URL = "https://viacep.com.br";

    private final RestClient restClient;

    public ViaCepClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    @Override
    @CircuitBreaker(name = "viaCepCB", fallbackMethod = "fallback")
    public CepResponse fetch(String cep) {
        ViaCepPayload payload = restClient.get()
                .uri("/ws/{cep}/json/", cep)
                .retrieve()
                .body(ViaCepPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ViaCEP retornou resposta vazia ou CEP não localizado.");
        }
        return payload.toCepResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CepResponse fallback(String cep, Throwable cause) {
        log.warn("ViaCEP fallback triggered for cep={} cause={}", cep, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ViaCEP indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ViaCepPayload(
            String cep,
            String logradouro,
            String complemento,
            String bairro,
            String localidade,
            String uf,
            String ibge
    ) {
        boolean isInvalid() {
            return cep == null || cep.isBlank();
        }

        CepResponse toCepResponse() {
            return new CepResponse(cep, logradouro, complemento, bairro, localidade, uf, ibge);
        }
    }
}
