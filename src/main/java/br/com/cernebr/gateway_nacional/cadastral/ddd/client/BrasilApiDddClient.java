package br.com.cernebr.gateway_nacional.cadastral.ddd.client;

import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Provider FALLBACK de DDD — BrasilAPI ({@code GET /api/ddd/v1/{ddd}}).
 *
 * <p><b>Posição na cascata:</b> {@link AnatelDddClient} é o tier 1
 * (consulta direta à fonte canônica ANATEL via CSV); este cliente entra
 * em ação <em>somente</em> quando o tier 1 falha — typically quando a
 * ANATEL está temporariamente fora do ar e nosso snapshot precisa de
 * refresh.</p>
 *
 * <p>BrasilAPI já entrega o shape final {@code {state, cities[]}} igual ao
 * nosso DTO — mapeamento 1:1, sem normalização.</p>
 */
@Slf4j
@Component
public class BrasilApiDddClient {

    public static final String PROVIDER_NAME = "BrasilAPI-DDD";

    private final RestClient restClient;

    public BrasilApiDddClient(RestClient.Builder builder,
                              @Value("${gateway.ddd.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "brasilApiDddCB", fallbackMethod = "fallback")
    public DddResponse findByDdd(String ddd) {
        try {
            BrasilApiDddPayload payload = restClient.get()
                    .uri("/api/ddd/v1/{ddd}", ddd)
                    .retrieve()
                    .body(BrasilApiDddPayload.class);

            if (payload == null || payload.state() == null) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "BrasilAPI retornou corpo vazio para DDD " + ddd);
            }
            return new DddResponse(payload.state(),
                    payload.cities() == null ? List.of() : payload.cities());
        } catch (HttpClientErrorException.NotFound nf) {
            // BrasilAPI confirma "DDD não existe" — resultado determinístico,
            // propaga 404 ao invés de tratar como falha de infra.
            throw new ResourceNotFoundException("DDD",
                    "DDD " + ddd + " não encontrado pela BrasilAPI (fallback).");
        }
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private DddResponse fallback(String ddd, Throwable cause) {
        if (cause instanceof ResourceNotFoundException rnf) {
            // Não mascarar 404 com 503 — manter semântica.
            throw rnf;
        }
        log.warn("BrasilAPI DDD fallback acionado para ddd={} cause={}", ddd, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI DDD indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiDddPayload(String state, List<String> cities) {
    }
}
