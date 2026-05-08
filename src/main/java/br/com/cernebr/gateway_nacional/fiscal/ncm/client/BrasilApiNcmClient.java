package br.com.cernebr.gateway_nacional.fiscal.ncm.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.fiscal.ncm.dto.NcmResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Primary NCM provider — BrasilAPI ({@code https://brasilapi.com.br}).
 *
 * <p>BrasilAPI proxies the Mercosul/Camex catalogue and exposes two
 * endpoints we care about:
 * <ul>
 *   <li>{@code GET /api/ncm/v1/{codigo}} — single entry by 8-digit code,
 *       returns 404 with no body when the code is unknown;</li>
 *   <li>{@code GET /api/ncm/v1?search={q}} — array of entries whose
 *       description (or code) matches; empty array when nothing matches.</li>
 * </ul>
 *
 * <p>The wire payload uses {@code snake_case} ({@code data_inicio},
 * {@code tipo_ato}). The {@link BrasilApiNcmPayload} record maps each
 * field via {@link JsonProperty} and projects to the canonical
 * {@link NcmResponse} record.</p>
 */
@Slf4j
@Component
public class BrasilApiNcmClient implements NcmClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-NCM";

    private static final ParameterizedTypeReference<List<BrasilApiNcmPayload>> PAYLOAD_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiNcmClient(RestClient.Builder builder,
                              @Value("${gateway.ncm.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiNcmCB", fallbackMethod = "findByCodigoFallback")
    public Optional<NcmResponse> findByCodigo(String codigo) {
        try {
            BrasilApiNcmPayload payload = restClient.get()
                    .uri("/api/ncm/v1/{codigo}", codigo)
                    .retrieve()
                    // 404 from BrasilAPI is a definitive "not found" — translate it
                    // to a synthetic "empty payload" rather than letting RestClient
                    // throw, so the service layer can distinguish "absent" from
                    // "unreachable" cleanly.
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {})
                    .body(BrasilApiNcmPayload.class);

            if (payload == null || payload.codigo() == null) {
                return Optional.empty();
            }
            return Optional.of(payload.toResponse());
        } catch (RestClientResponseException ex) {
            // Any 4xx other than 404 is treated as "unavailable" and bubbles
            // through the cascade. 404 was filtered out above already.
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI devolveu " + ex.getStatusCode() + " para NCM " + codigo + ".", ex);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI inacessível para NCM " + codigo + ": " + ex.getClass().getSimpleName(), ex);
        }
    }

    @Override
    @CircuitBreaker(name = "brasilApiNcmCB", fallbackMethod = "searchFallback")
    public List<NcmResponse> searchByDescricao(String descricao) {
        try {
            List<BrasilApiNcmPayload> payload = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/ncm/v1")
                            .queryParam("search", descricao)
                            .build())
                    .retrieve()
                    .body(PAYLOAD_LIST_TYPE);

            if (payload == null || payload.isEmpty()) {
                return Collections.emptyList();
            }
            return payload.stream().map(BrasilApiNcmPayload::toResponse).toList();
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI search inacessível: " + ex.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private Optional<NcmResponse> findByCodigoFallback(String codigo, Throwable cause) {
        log.warn("BrasilAPI-NCM fallback (findByCodigo={}): {}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private List<NcmResponse> searchFallback(String descricao, Throwable cause) {
        log.warn("BrasilAPI-NCM fallback (search='{}'): {}", descricao, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Wire-shape of the BrasilAPI NCM payload. Single-result endpoint
     * returns one of these as object; search returns a list of these.
     * Field names are mapped via {@link JsonProperty} from the upstream's
     * {@code snake_case} convention.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiNcmPayload(
            String codigo,
            String descricao,
            @JsonProperty("data_inicio") LocalDate dataInicio,
            @JsonProperty("data_fim") LocalDate dataFim,
            @JsonProperty("tipo_ato") String tipoAto,
            @JsonProperty("numero_ato") String numeroAto,
            @JsonProperty("ano_ato") Integer anoAto
    ) {
        NcmResponse toResponse() {
            return new NcmResponse(codigo, descricao, dataInicio, dataFim, tipoAto, numeroAto, anoAto);
        }
    }
}
