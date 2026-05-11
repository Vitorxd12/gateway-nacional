package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.MoedaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider de fallback do catálogo de moedas via BrasilAPI
 * ({@code GET /api/cambio/v1/moedas}). A BrasilAPI já consome o mesmo
 * endpoint OLINDA internamente e devolve a lista normalizada — atua como
 * rede de proteção quando o BCB OLINDA pica/pisca, mantendo a rota
 * pública {@code /api/v1/financeiro/cambio/moedas} disponível.
 */
@Slf4j
@Component
public class BrasilApiMoedasClient implements MoedasCatalogClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Moedas";
    private static final String PATH = "/api/cambio/v1/moedas";

    private final RestClient restClient;

    public BrasilApiMoedasClient(RestClient.Builder builder,
                                 @Value("${gateway.cambio.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "cambioMoedasBrasilApiCB", fallbackMethod = "fallback")
    public List<MoedaResponse> listAll() {
        List<BrasilApiMoeda> payload = restClient.get()
                .uri(PATH)
                .retrieve()
                .body(BRASIL_API_LIST);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI /moedas devolveu corpo vazio.");
        }

        List<MoedaResponse> out = new ArrayList<>(payload.size());
        for (BrasilApiMoeda m : payload) {
            if (m.simbolo() == null || m.simbolo().isBlank()) continue;
            out.add(new MoedaResponse(m.simbolo(), m.nome(), m.tipoMoeda()));
        }
        return out;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<MoedaResponse> fallback(Throwable cause) {
        log.warn("BrasilAPI /moedas fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI /moedas indisponível ou Circuit Breaker aberto.", cause);
    }

    private static final org.springframework.core.ParameterizedTypeReference<List<BrasilApiMoeda>> BRASIL_API_LIST =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiMoeda(String simbolo, String nome, String tipo_moeda) {
        String tipoMoeda() { return tipo_moeda; }
    }
}
