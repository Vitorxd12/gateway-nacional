package br.com.cernebr.gateway_nacional.cadastral.cnae.client;

import br.com.cernebr.gateway_nacional.cadastral.cnae.dto.CnaeResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Primary CNAE provider — IBGE servicodados
 * ({@code https://servicodados.ibge.gov.br/api/v2/cnae/subclasses/{codigo}}).
 *
 * <p>The IBGE endpoint is the canonical source for CNAE data: the same
 * IBGE that maintains the official tables. Two important shape notes:
 * <ul>
 *   <li><b>Subclasse vs classe</b>: the {@code /subclasses/{id}} path
 *       takes the 7-digit subclass code (matching the Receita Federal /
 *       CNPJ convention). The legacy {@code /classes/{id}} path only
 *       accepted 5 digits and was insufficient for our use case.</li>
 *   <li><b>"Not found" shape</b>: when the code does not exist, IBGE
 *       returns HTTP 200 with an empty array {@code []} for the list
 *       endpoint. The single-resource endpoint we use here returns
 *       HTTP 404 — translated to {@link Optional#empty()}.</li>
 * </ul>
 */
@Slf4j
@Component
public class IbgeCnaeClient implements CnaeClientProvider {

    public static final String PROVIDER_NAME = "IBGE-CNAE";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public IbgeCnaeClient(RestClient.Builder builder,
                          @Value("${gateway.cnae.ibge.base-url:https://servicodados.ibge.gov.br}") String baseUrl,
                          ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "ibgeCnaeCB", fallbackMethod = "fallback")
    public Optional<CnaeResponse> findByCodigo(String codigo) {
        String body;
        try {
            body = restClient.get()
                    .uri("/api/v2/cnae/subclasses/{codigo}", codigo)
                    .retrieve()
                    // 4xx → translated to "absent" rather than letting it throw.
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {})
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            // Non-2xx that bypassed the onStatus filter — surface as unavailable
            // so the cascade triggers the local snapshot.
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "IBGE devolveu " + ex.getStatusCode() + " para CNAE " + codigo + ".", ex);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "IBGE inacessível para CNAE " + codigo + ": " + ex.getClass().getSimpleName(), ex);
        }

        if (body == null || body.isBlank()) {
            return Optional.empty();
        }

        // IBGE quirk: for an unknown subclass the endpoint returns HTTP 200
        // with the literal body `[]` (an empty array), instead of 404 or an
        // empty object. Deserialising `[]` as `IbgeCnaePayload` blows up
        // Jackson, which would falsely look like an upstream outage and trip
        // the cascade. Detect the array prefix and short-circuit.
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("[")) {
            return Optional.empty();
        }

        try {
            IbgeCnaePayload payload = objectMapper.readValue(body, IbgeCnaePayload.class);
            if (payload == null || payload.id() == null) {
                return Optional.empty();
            }
            return Optional.of(payload.toResponse());
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "IBGE devolveu corpo não-JSON para CNAE " + codigo + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private Optional<CnaeResponse> fallback(String codigo, Throwable cause) {
        log.warn("IBGE-CNAE fallback (findByCodigo={}): {}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "IBGE indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Wire-shape of the IBGE servicodados subclasses endpoint. The actual
     * response carries the full hierarchy ({@code classe}, {@code grupo},
     * {@code divisao}, {@code secao}) — we ignore everything except the
     * top-level {@code id}/{@code descricao} since our public DTO only
     * exposes the subclass.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IbgeCnaePayload(String id, String descricao) {
        CnaeResponse toResponse() {
            return new CnaeResponse(id, descricao);
        }
    }
}
