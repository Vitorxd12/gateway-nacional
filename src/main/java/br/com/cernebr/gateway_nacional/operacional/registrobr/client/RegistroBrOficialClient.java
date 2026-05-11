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
 * Tier 1 — consulta direta ao endpoint público do NIC.br
 * {@code https://registro.br/v2/ajax/avail/raw/{dominio}}.
 *
 * <p>O Registro.br responde JSON com campos crus em snake_case e enum-strings
 * próprios ({@code AVAILABLE}, {@code UNAVAILABLE}, {@code WAITING},
 * {@code EXPIRED}). O mapeamento abaixo normaliza para o contrato camelCase
 * exposto pelo Gateway e calcula {@code disponivel} de forma determinística.</p>
 */
@Slf4j
@Component
public class RegistroBrOficialClient implements RegistroBrClientProvider {

    public static final String PROVIDER_NAME = "Registro.br";
    private static final String PATH = "/v2/ajax/avail/raw/{dominio}";

    private final RestClient restClient;

    public RegistroBrOficialClient(RestClient.Builder builder,
                                   @Value("${gateway.registrobr.oficial.base-url:https://registro.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl)
                // O endpoint ajax exige User-Agent não-default para retornar JSON.
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; GatewayNacional/1.0)")
                .build();
    }

    @Override
    @CircuitBreaker(name = "registroBrOficialCB", fallbackMethod = "fallback")
    public RegistroBrResponse consultar(String dominio) {
        String canonical = dominio.toLowerCase(Locale.ROOT);
        RegistroBrPayload payload = restClient.get()
                .uri(PATH, canonical)
                .retrieve()
                .body(RegistroBrPayload.class);

        if (payload == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Registro.br devolveu corpo vazio para domínio=" + canonical);
        }

        return new RegistroBrResponse(
                canonical,
                payload.status,
                isDisponivel(payload.status),
                payload.reasons != null && !payload.reasons.isEmpty() ? payload.reasons.get(0) : null,
                payload.publication_status,
                payload.suggestions,
                PROVIDER_NAME
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private static boolean isDisponivel(String status) {
        return "AVAILABLE".equalsIgnoreCase(status);
    }

    @SuppressWarnings("unused")
    private RegistroBrResponse fallback(String dominio, Throwable cause) {
        log.warn("Registro.br oficial fallback acionado para dominio={} cause={}",
                dominio, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Registro.br indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegistroBrPayload(
            String status,
            String publication_status,
            List<String> reasons,
            List<String> suggestions
    ) {}
}
