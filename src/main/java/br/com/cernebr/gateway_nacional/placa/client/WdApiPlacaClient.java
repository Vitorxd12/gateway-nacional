package br.com.cernebr.gateway_nacional.placa.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.placa.dto.PlacaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Primary placa provider — WDApi (https://wdapi2.com.br).
 *
 * <p>WDApi field names are inconsistent — vehicle attributes arrive in
 * UPPERCASE ({@code MARCA}, {@code MODELO}) while location attributes come
 * lowercase. The Anti-Corruption Layer absorbs that with explicit
 * {@link JsonProperty} mappings and exposes a clean DTO.</p>
 */
@Slf4j
@Component
public class WdApiPlacaClient implements PlacaClientProvider {

    public static final String PROVIDER_NAME = "WDApi";

    private final RestClient restClient;

    public WdApiPlacaClient(RestClient.Builder builder,
                            @Value("${gateway.placa.wdapi.base-url:https://wdapi2.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "wdApiPlacaCB", fallbackMethod = "fallback")
    public PlacaResponse fetchByPlaca(String placa) {
        WdApiPayload payload = restClient.get()
                .uri("/consulta/{placa}", placa)
                .retrieve()
                .body(WdApiPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "WDApi retornou resposta vazia ou placa não localizada.");
        }
        return payload.toPlacaResponse(placa);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private PlacaResponse fallback(String placa, Throwable cause) {
        log.warn("WDApi fallback triggered for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "WDApi indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WdApiPayload(
            String placa,
            @JsonProperty("MARCA") String marca,
            @JsonProperty("MODELO") String modelo,
            @JsonProperty("ano") Integer anoFabricacao,
            @JsonProperty("anoModelo") Integer anoModelo,
            String chassi,
            String municipio,
            String uf
    ) {
        boolean isInvalid() {
            return marca == null && modelo == null;
        }

        PlacaResponse toPlacaResponse(String requestedPlaca) {
            String canonicalPlaca = (placa != null && !placa.isBlank()) ? placa : requestedPlaca;
            // codigoFipe = null: WDApi não publica essa associação.
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
