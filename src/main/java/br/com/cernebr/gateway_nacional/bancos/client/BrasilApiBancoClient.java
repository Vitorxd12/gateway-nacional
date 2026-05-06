package br.com.cernebr.gateway_nacional.bancos.client;

import br.com.cernebr.gateway_nacional.bancos.dto.BancoResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Primary bank-catalogue provider — BrasilAPI (https://brasilapi.com.br).
 *
 * <p>BrasilAPI returns the COMPE code as a JSON number (sometimes {@code null}
 * for institutions that only participate in PIX). The Anti-Corruption Layer
 * converts that to the canonical zero-padded 3-digit string.</p>
 */
@Slf4j
@Component
public class BrasilApiBancoClient implements BancoClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Bancos";

    private static final ParameterizedTypeReference<List<BrasilApiBancoPayload>> PAYLOAD_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiBancoClient(RestClient.Builder builder,
                                @Value("${gateway.bancos.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiBancosCB", fallbackMethod = "fallbackAll")
    public List<BancoResponse> fetchAll() {
        List<BrasilApiBancoPayload> payload = restClient.get()
                .uri("/api/bancos/v1")
                .retrieve()
                .body(PAYLOAD_LIST_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia de bancos.");
        }
        return payload.stream().map(BrasilApiBancoPayload::toBancoResponse).toList();
    }

    @Override
    @CircuitBreaker(name = "brasilApiBancosCB", fallbackMethod = "fallbackByCodigo")
    public BancoResponse fetchByCodigo(String codigo) {
        BrasilApiBancoPayload payload = restClient.get()
                .uri("/api/bancos/v1/{codigo}", codigo)
                .retrieve()
                .body(BrasilApiBancoPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou resposta vazia para o código informado.");
        }
        return payload.toBancoResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<BancoResponse> fallbackAll(Throwable cause) {
        log.warn("BrasilAPI (Bancos) fallback triggered for fetchAll cause={}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private BancoResponse fallbackByCodigo(String codigo, Throwable cause) {
        log.warn("BrasilAPI (Bancos) fallback triggered for codigo={} cause={}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiBancoPayload(String ispb, String name, Integer code, String fullName) {
        boolean isInvalid() {
            return ispb == null || ispb.isBlank();
        }

        BancoResponse toBancoResponse() {
            String codigo = (code != null) ? String.format("%03d", code) : null;
            return new BancoResponse(ispb, name, codigo, fullName);
        }
    }
}
