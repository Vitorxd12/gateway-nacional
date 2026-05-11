package br.com.cernebr.gateway_nacional.cadastral.cep.client;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse.Localizacao;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Geocodificação via BrasilAPI v2 ({@code GET /api/cep/v2/{cep}}).
 *
 * <p>A BrasilAPI v2 já consolida internamente a chamada ao Nominatim/OSM com
 * a lógica de filtro por postcode (exato 8 dígitos → fallback 5 dígitos) — usar
 * o proxy dela aqui dá uma rede de proteção testada em produção e resposta
 * cacheada do lado deles, sem pagar duplo round-trip ao OSM.</p>
 *
 * <p>Ignoramos os campos de endereço retornados (já temos do tier 1) e
 * extraímos apenas {@code location.coordinates}. CB próprio
 * ({@code brasilApiV2GeoCB}) — isolado do v1 porque os caminhos têm
 * características de latência distintas (v2 é mais lento por causa do
 * round-trip interno ao Nominatim).</p>
 */
@Slf4j
@Component
public class BrasilApiV2GeoClient implements GeoCepClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-v2-Geo";

    private final RestClient restClient;

    public BrasilApiV2GeoClient(RestClient.Builder builder,
                                @Value("${gateway.cep.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiV2GeoCB", fallbackMethod = "fallback")
    public Optional<Localizacao> geocodificar(CepResponse base) {
        if (base == null || base.cep() == null || base.cep().isBlank()) {
            return Optional.empty();
        }
        BrasilApiV2Payload payload;
        try {
            payload = restClient.get()
                    .uri("/api/cep/v2/{cep}", base.cep().replaceAll("\\D", ""))
                    .retrieve()
                    .body(BrasilApiV2Payload.class);
        } catch (HttpClientErrorException.NotFound e) {
            // CEP não localizado — semântica determinística, não é falha do provider.
            return Optional.empty();
        } catch (HttpClientErrorException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI v2 devolveu HTTP " + ex.getStatusCode().value() + " para CEP " + base.cep(), ex);
        }

        if (payload == null || payload.location == null
                || payload.location.coordinates == null) {
            return Optional.empty();
        }
        BrasilApiCoordinates c = payload.location.coordinates;
        if (c.latitude == null || c.longitude == null
                || c.latitude.isBlank() || c.longitude.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Localizacao(
                    new BigDecimal(c.latitude.trim()),
                    new BigDecimal(c.longitude.trim()),
                    "EXATA",
                    PROVIDER_NAME
            ));
        } catch (NumberFormatException ex) {
            log.warn("BrasilAPI v2 devolveu lat/long inválido para cep={}: lat={} lon={}",
                    base.cep(), c.latitude, c.longitude);
            return Optional.empty();
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private Optional<Localizacao> fallback(CepResponse base, Throwable cause) {
        log.warn("BrasilAPI v2 geo fallback acionado para cep={}: {}",
                base != null ? base.cep() : null, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI v2 geo indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiV2Payload(BrasilApiLocation location) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiLocation(String type, BrasilApiCoordinates coordinates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiCoordinates(String longitude, String latitude) {}
}
