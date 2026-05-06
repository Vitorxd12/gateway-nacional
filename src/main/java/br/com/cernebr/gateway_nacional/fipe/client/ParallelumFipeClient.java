package br.com.cernebr.gateway_nacional.fipe.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.fipe.dto.FipePrecoResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Secondary FIPE provider — Parallelum (https://parallelum.com.br).
 *
 * <p>Returns a single year-quote per call. Field names use PascalCase
 * ({@code Valor}, {@code Marca}, {@code Modelo}…) which is mapped via
 * {@link JsonProperty} in the Anti-Corruption Layer.</p>
 *
 * <p>The path is exposed as a property to absorb future API contract changes
 * without code modification.</p>
 */
@Slf4j
@Component
public class ParallelumFipeClient implements FipeClientProvider {

    public static final String PROVIDER_NAME = "Parallelum";

    private final RestClient restClient;
    private final String path;

    public ParallelumFipeClient(RestClient.Builder builder,
                                @Value("${gateway.fipe.parallelum.base-url:https://parallelum.com.br}") String baseUrl,
                                @Value("${gateway.fipe.parallelum.path:/fipe/api/v1/carros/veiculos/{codigoFipe}/{anoModelo}}") String path) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.path = path;
    }

    @Override
    @CircuitBreaker(name = "parallelumFipeCB", fallbackMethod = "fallback")
    public FipePrecoResponse fetchPreco(String codigoFipe, String anoModelo) {
        ParallelumPayload payload = restClient.get()
                .uri(path, codigoFipe, anoModelo)
                .retrieve()
                .body(ParallelumPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Parallelum retornou resposta vazia para a combinação código/ano informada.");
        }
        return payload.toFipePrecoResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private FipePrecoResponse fallback(String codigoFipe, String anoModelo, Throwable cause) {
        log.warn("Parallelum (FIPE) fallback triggered for codigoFipe={} anoModelo={} cause={}",
                codigoFipe, anoModelo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Parallelum indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Strips Brazilian currency formatting ({@code "R$ 80.444,00"}) into a
     * {@link BigDecimal}.
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
    private record ParallelumPayload(
            @JsonProperty("Valor") String valor,
            @JsonProperty("Marca") String marca,
            @JsonProperty("Modelo") String modelo,
            @JsonProperty("AnoModelo") Integer anoModelo,
            @JsonProperty("Combustivel") String combustivel,
            @JsonProperty("CodigoFipe") String codigoFipe,
            @JsonProperty("MesReferencia") String mesReferencia
    ) {
        boolean isInvalid() {
            return codigoFipe == null || codigoFipe.isBlank() || anoModelo == null;
        }

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
