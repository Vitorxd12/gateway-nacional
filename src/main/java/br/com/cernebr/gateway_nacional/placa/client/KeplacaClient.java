package br.com.cernebr.gateway_nacional.placa.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.placa.dto.PlacaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Secondary placa provider — Keplaca (https://api.keplaca.com).
 *
 * <p>Authenticated via the {@code Authorization} header. The default token
 * value {@code "your_token_here"} is a deliberate placeholder — the client
 * <b>short-circuits before any network call</b> when the placeholder is
 * detected, throwing {@link ResourceUnavailableException} so the orchestrator
 * cascades cleanly without a NullPointer or a wasted upstream round-trip.</p>
 *
 * <p><b>CB note:</b> when the token is the placeholder, every call throws
 * pre-flight, which trips {@code keplacaCB} after the failure threshold.
 * That's intentional: subsequent calls short-circuit through the CB without
 * even reaching the placeholder check, accelerating the cascade in
 * misconfigured environments. In production with a real token, this never
 * happens.</p>
 */
@Slf4j
@Component
public class KeplacaClient implements PlacaClientProvider {

    public static final String PROVIDER_NAME = "Keplaca";

    private static final String PLACEHOLDER_TOKEN = "your_token_here";

    private final RestClient restClient;
    private final String token;

    public KeplacaClient(RestClient.Builder builder,
                         @Value("${gateway.placa.keplaca.base-url:https://api.keplaca.com}") String baseUrl,
                         @Value("${gateway.placa.keplaca.token:your_token_here}") String token) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.token = token;
    }

    @Override
    @CircuitBreaker(name = "keplacaCB", fallbackMethod = "fallback")
    public PlacaResponse fetchByPlaca(String placa) {
        if (!isConfigured()) {
            log.info("Keplaca token não configurado (placeholder detectado); pulando provider para placa={}", placa);
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Keplaca não configurado: token ausente ou placeholder.");
        }

        KeplacaPayload payload = restClient.get()
                .uri("/v1/placa/{placa}", placa)
                .header("Authorization", token)
                .retrieve()
                .body(KeplacaPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Keplaca retornou resposta vazia ou placa não localizada.");
        }
        return payload.toPlacaResponse(placa);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private PlacaResponse fallback(String placa, Throwable cause) {
        log.warn("Keplaca fallback triggered for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Keplaca indisponível, sem credenciais ou Circuit Breaker aberto.", cause);
    }

    private boolean isConfigured() {
        return token != null && !token.isBlank() && !PLACEHOLDER_TOKEN.equals(token);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KeplacaPayload(
            String placa,
            String marca,
            String modelo,
            Integer anoFabricacao,
            Integer anoModelo,
            String chassi,
            String municipio,
            String uf
    ) {
        boolean isInvalid() {
            return marca == null && modelo == null;
        }

        PlacaResponse toPlacaResponse(String requestedPlaca) {
            String canonicalPlaca = (placa != null && !placa.isBlank()) ? placa : requestedPlaca;
            // codigoFipe = null: Keplaca não publica essa associação.
            return new PlacaResponse(
                    canonicalPlaca,
                    marca,
                    modelo,
                    anoFabricacao != null ? anoFabricacao : 0,
                    anoModelo != null ? anoModelo : 0,
                    ChassiMask.mask(chassi),
                    municipio,
                    uf,
                    null
            );
        }
    }
}
