package br.com.cernebr.gateway_nacional.operacional.registrobr.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.operacional.registrobr.dto.RegistroBrResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;

/**
 * Tier 2 — proxy BrasilAPI {@code /api/registrobr/v1/{dominio}}. A BrasilAPI
 * já normaliza o retorno do Registro.br em campos {@code status_code} e
 * {@code status} textual; aqui só convertemos para o contrato camelCase
 * comum do Gateway.
 */
@Slf4j
@Component
public class BrasilApiRegistroBrClient implements RegistroBrClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-RegistroBR";
    private static final String PATH = "/api/registrobr/v1/{dominio}";

    private final RestClient restClient;

    public BrasilApiRegistroBrClient(RestClient.Builder builder,
                                     @Value("${gateway.registrobr.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "registroBrBrasilApiCB", fallbackMethod = "fallback")
    public RegistroBrResponse consultar(String dominio) {
        String canonical = dominio.toLowerCase(Locale.ROOT);
        BrasilApiPayload p = restClient.get()
                .uri(PATH, canonical)
                .retrieve()
                .body(BrasilApiPayload.class);

        if (p == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI Registro.br devolveu corpo vazio para " + canonical);
        }

        return new RegistroBrResponse(
                canonical,
                p.status,
                "AVAILABLE".equalsIgnoreCase(p.status),
                p.reasons != null && !p.reasons.isEmpty() ? p.reasons.get(0) : null,
                p.publication_status,
                p.suggestions,
                PROVIDER_NAME
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private RegistroBrResponse fallback(String dominio, Throwable cause) {
        log.warn("BrasilAPI Registro.br fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI Registro.br indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiPayload(
            Integer status_code,
            String status,
            String publication_status,
            List<String> reasons,
            List<String> suggestions
    ) {}
}
