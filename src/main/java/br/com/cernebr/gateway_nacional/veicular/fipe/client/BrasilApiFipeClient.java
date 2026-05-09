package br.com.cernebr.gateway_nacional.veicular.fipe.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Primary FIPE provider — BrasilAPI (https://brasilapi.com.br).
 *
 * <p>BrasilAPI's endpoint {@code /api/fipe/preco/v1/{codigoFipe}} returns
 * <em>all</em> available year-fuel combinations for a given FIPE code as an
 * array. The Anti-Corruption Layer filters by {@code anoModelo} client-side
 * and converts the Brazilian-formatted price string ({@code "R$ 80.444,00"})
 * into a {@link BigDecimal}.</p>
 */
@Slf4j
@Component
public class BrasilApiFipeClient implements FipeClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Fipe";

    private static final ParameterizedTypeReference<List<BrasilApiFipePayload>> PAYLOAD_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiFipeClient(RestClient.Builder builder,
                               @Value("${gateway.fipe.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiFipeCB", fallbackMethod = "fallback")
    public FipePrecoResponse fetchPreco(String codigoFipe, String anoModelo) {
        List<BrasilApiFipePayload> payload = restClient.get()
                .uri("/api/fipe/preco/v1/{codigoFipe}", codigoFipe)
                .retrieve()
                .body(PAYLOAD_LIST_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia para o código FIPE informado.");
        }

        int targetYear = parseAnoModelo(anoModelo);
        return payload.stream()
                .filter(item -> item.anoModelo() == targetYear)
                .findFirst()
                .map(BrasilApiFipePayload::toFipePrecoResponse)
                .orElseThrow(() -> new ResourceUnavailableException(PROVIDER_NAME,
                        "Ano modelo " + anoModelo + " não disponível no catálogo BrasilAPI para este código."));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private FipePrecoResponse fallback(String codigoFipe, String anoModelo, Throwable cause) {
        log.warn("BrasilAPI (FIPE) fallback triggered for codigoFipe={} anoModelo={} cause={}",
                codigoFipe, anoModelo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    private static int parseAnoModelo(String anoModelo) {
        try {
            return Integer.parseInt(anoModelo);
        } catch (NumberFormatException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Ano modelo inválido: " + anoModelo, ex);
        }
    }

    /**
     * Strips Brazilian currency formatting ({@code "R$ 80.444,00"}) into a
     * {@link BigDecimal}. {@code .} is the thousand separator; {@code ,} is
     * the decimal separator.
     */
    private static BigDecimal parseBRCurrency(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            return null;
        }
        String cleaned = formatted
                .replace("R$", "")
                .replace(".", "")
                .replace(",", ".")
                .trim();
        return new BigDecimal(cleaned);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiFipePayload(
            String valor,
            String marca,
            String modelo,
            int anoModelo,
            String combustivel,
            String codigoFipe,
            String mesReferencia
    ) {
        FipePrecoResponse toFipePrecoResponse() {
            return new FipePrecoResponse(
                    codigoFipe,
                    marca,
                    modelo,
                    anoModelo,
                    combustivel,
                    parseBRCurrency(valor),
                    mesReferencia
            );
        }
    }
}
