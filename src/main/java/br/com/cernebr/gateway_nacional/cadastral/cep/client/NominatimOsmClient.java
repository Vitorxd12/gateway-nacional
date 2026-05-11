package br.com.cernebr.gateway_nacional.cadastral.cep.client;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse.Localizacao;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Geocodificação via Nominatim/OpenStreetMap direto
 * ({@code https://nominatim.openstreetmap.org/search}).
 *
 * <p>É a mesma fonte que a BrasilAPI v2 consulta internamente — usá-la em
 * paralelo (sob hedge com {@link BrasilApiV2GeoClient}) garante que o
 * gateway responde lat/long mesmo se a BrasilAPI estiver fora.</p>
 *
 * <h2>Filtragem por postcode</h2>
 * <p>O Nominatim devolve até dezenas de candidatos para uma busca
 * {@code state+city+street}. Replicamos a heurística da BrasilAPI:
 * <ol>
 *   <li>Preferir o resultado cujo {@code address.postcode} bate <b>todos os
 *       8 dígitos</b> do CEP consultado (precisão {@code EXATA});</li>
 *   <li>Se nenhum bater 8 dígitos, aceitar o primeiro com prefixo de
 *       <b>5 dígitos</b> coincidente (precisão {@code APROXIMADA}, escala de
 *       bairro);</li>
 *   <li>Se ainda assim nenhum, devolver {@link Optional#empty()} — não
 *       fabricamos coordenada.</li>
 * </ol>
 *
 * <h2>Política do Nominatim</h2>
 * <p>O servidor público exige {@code User-Agent} identificável e limita
 * 1 req/s por IP — daí o {@link CircuitBreaker} {@code nominatimOsmCB} com
 * timeout curto (3s) e o cache de 30d sobre o resultado já evitar tráfego
 * recorrente. Para deploys self-host de alto volume, o operador pode
 * apontar {@code gateway.cep.nominatim.base-url} para uma instância privada
 * (Docker {@code mediagis/nominatim}).</p>
 */
@Slf4j
@Component
public class NominatimOsmClient implements GeoCepClientProvider {

    public static final String PROVIDER_NAME = "OpenStreetMap-Nominatim";

    private final RestClient restClient;

    public NominatimOsmClient(RestClient.Builder builder,
                              @Value("${gateway.cep.nominatim.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
                              @Value("${gateway.cep.nominatim.user-agent:GatewayNacional/1.0 (+https://github.com/cernebr/gateway-nacional)}") String userAgent) {
        this.restClient = builder.baseUrl(baseUrl)
                // Política do Nominatim — User-Agent identificável é obrigatório.
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    @CircuitBreaker(name = "nominatimOsmCB", fallbackMethod = "fallback")
    public Optional<Localizacao> geocodificar(CepResponse base) {
        if (base == null || base.uf() == null || base.localidade() == null) {
            return Optional.empty();
        }
        String streetNoSuffix = stripStreetSuffix(base.logradouro());
        List<NominatimResult> results = restClient.get()
                .uri(uri -> {
                    uri.path("/search")
                            .queryParam("format", "json")
                            .queryParam("addressdetails", "1")
                            .queryParam("country", "Brasil")
                            .queryParam("state", base.uf())
                            .queryParam("city", base.localidade());
                    if (streetNoSuffix != null && !streetNoSuffix.isBlank()) {
                        uri.queryParam("street", streetNoSuffix);
                    }
                    return uri.build();
                })
                .retrieve()
                .body(LIST_RESULT);

        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        return matchByPostcode(base.cep(), results);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /** Remove sufixos descritivos do logradouro ("- lado ímpar", "-1ª quadra")
     *  que confundem a busca textual do Nominatim. Mesma heurística da BrasilAPI. */
    private static String stripStreetSuffix(String logradouro) {
        if (logradouro == null) return null;
        int dashIdx = logradouro.indexOf('-');
        return (dashIdx > 0 ? logradouro.substring(0, dashIdx) : logradouro).trim();
    }

    private static Optional<Localizacao> matchByPostcode(String cepConsultado, List<NominatimResult> results) {
        if (cepConsultado == null) {
            return toLocalizacao(results.get(0), "APROXIMADA");
        }
        String cepClean = cepConsultado.replaceAll("\\D", "");
        String cepPrefix = cepClean.length() >= 5 ? cepClean.substring(0, 5) : cepClean;

        // Tier A: bate os 8 dígitos exatos
        for (NominatimResult r : results) {
            String pc = r.cleanPostcode();
            if (pc != null && pc.equals(cepClean)) {
                return toLocalizacao(r, "EXATA");
            }
        }
        // Tier B: bate os 5 primeiros dígitos (mesma região postal)
        for (NominatimResult r : results) {
            String pc = r.cleanPostcode();
            if (pc != null && pc.length() >= 5 && pc.substring(0, 5).equals(cepPrefix)) {
                return toLocalizacao(r, "APROXIMADA");
            }
        }
        return Optional.empty();
    }

    private static Optional<Localizacao> toLocalizacao(NominatimResult r, String precisao) {
        if (r.lat == null || r.lon == null || r.lat.isBlank() || r.lon.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Localizacao(
                    new BigDecimal(r.lat.trim()),
                    new BigDecimal(r.lon.trim()),
                    precisao,
                    PROVIDER_NAME
            ));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unused")
    private Optional<Localizacao> fallback(CepResponse base, Throwable cause) {
        log.warn("Nominatim OSM fallback acionado para cep={}: {}",
                base != null ? base.cep() : null, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Nominatim OSM indisponível ou Circuit Breaker aberto.", cause);
    }

    private static final ParameterizedTypeReference<List<NominatimResult>> LIST_RESULT =
            new ParameterizedTypeReference<>() {};

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NominatimResult(String lat, String lon, NominatimAddress address) {
        String cleanPostcode() {
            if (address == null || address.postcode == null) return null;
            return address.postcode.replaceAll("\\D", "");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NominatimAddress(String postcode) {}
}
