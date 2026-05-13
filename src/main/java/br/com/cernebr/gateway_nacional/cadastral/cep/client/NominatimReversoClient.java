package br.com.cernebr.gateway_nacional.cadastral.cep.client;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Geocodificação reversa via Nominatim/OpenStreetMap.
 *
 * <p>Recebe latitude e longitude e devolve o endereço e o CEP correspondentes,
 * resolvidos pelo {@code GET /reverse?format=jsonv2&lat={lat}&lon={lon}&addressdetails=1}
 * do Nominatim. É a peça que habilita a funcionalidade "clique no mapa → retorna CEP".</p>
 *
 * <h2>Mapeamento para CepResponse</h2>
 * <ul>
 *   <li>{@code cep} ← {@code address.postcode} (limpo de hífen)</li>
 *   <li>{@code logradouro} ← {@code address.road}</li>
 *   <li>{@code bairro} ← {@code address.suburb} ou {@code address.neighbourhood}</li>
 *   <li>{@code localidade} ← {@code address.city} ou {@code address.town} ou {@code address.village}</li>
 *   <li>{@code uf} ← {@code address.state_code} (ISO 3166-2:BR → 2 chars sem prefixo)</li>
 *   <li>{@code localizacao} ← lat/lon fornecidos pelo caller (precisão EXATA — ponto do mapa)</li>
 * </ul>
 *
 * <h2>Quando retorna Optional.empty()</h2>
 * <ul>
 *   <li>O Nominatim não encontrou nenhum endereço para as coordenadas (zona remota, oceano).</li>
 *   <li>O resultado encontrado não possui {@code address.postcode} (estrada sem CEP).</li>
 * </ul>
 *
 * <h2>Política do Nominatim</h2>
 * <p>Usuário deve fornecer {@code User-Agent} identificável; servidor público
 * limita ~1 req/s por IP. O cache Redis (TTL 30d) absorve a maior parte das
 * consultas recorrentes de coordenadas próximas. Para deploys de alto volume,
 * apontar {@code gateway.cep.nominatim.base-url} para instância privada.</p>
 */
@Slf4j
@Component
public class NominatimReversoClient {

    public static final String PROVIDER_NAME = "OpenStreetMap-Nominatim-Reverso";

    private final RestClient restClient;

    public NominatimReversoClient(
            RestClient.Builder builder,
            @Value("${gateway.cep.nominatim.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
            @Value("${gateway.cep.nominatim.user-agent:GatewayNacional/1.0 (+https://github.com/cernebr/gateway-nacional)}") String userAgent) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Geocodifica de forma reversa: coordenadas → endereço com CEP.
     *
     * @param lat latitude WGS84 em graus decimais
     * @param lon longitude WGS84 em graus decimais
     * @return optional com o {@link CepResponse} preenchido, ou empty se sem resultado
     * @throws ResourceUnavailableException quando o Nominatim falha ou o CB está aberto
     */
    @CircuitBreaker(name = "nominatimReversoCB", fallbackMethod = "fallback")
    public Optional<CepResponse> reverso(BigDecimal lat, BigDecimal lon) {
        NominatimReverseResult result = restClient.get()
                .uri(uri -> uri.path("/reverse")
                        .queryParam("format", "jsonv2")
                        .queryParam("lat", lat.toPlainString())
                        .queryParam("lon", lon.toPlainString())
                        .queryParam("addressdetails", "1")
                        .build())
                .retrieve()
                .body(NominatimReverseResult.class);

        if (result == null || result.address == null) {
            log.debug("Nominatim reverso retornou resposta vazia para lat={} lon={}", lat, lon);
            return Optional.empty();
        }

        String cep = result.address.cleanPostcode();
        if (cep == null || cep.isBlank()) {
            log.debug("Nominatim reverso: endereço encontrado mas sem postcode para lat={} lon={}", lat, lon);
            return Optional.empty();
        }

        String uf = result.address.stateCode();
        String logradouro = result.address.road;
        String bairro = result.address.bairro();
        String localidade = result.address.localidade();

        // Coordenadas fornecidas pelo caller — precisão máxima possível.
        CepResponse.Localizacao loc = new CepResponse.Localizacao(lat, lon, "EXATA", PROVIDER_NAME);

        CepResponse response = new CepResponse(
                formatCep(cep),
                logradouro,
                null,
                bairro,
                localidade,
                uf,
                null,    // ibge: preenchido pelo IbgeEnrichmentService depois
                loc
        );

        log.debug("Nominatim reverso resolvido: cep={} localidade={} uf={}", cep, localidade, uf);
        return Optional.of(response);
    }

    @SuppressWarnings("unused")
    private Optional<CepResponse> fallback(BigDecimal lat, BigDecimal lon, Throwable cause) {
        log.warn("Nominatim reverso fallback lat={} lon={} causa={}", lat, lon, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Nominatim reverso indisponível ou Circuit Breaker aberto.", cause);
    }

    // ── Formatação ──────────────────────────────────────────────────────────────

    /** Garante o formato "NNNNN-NNN" padrão ViaCEP para CEPs de 8 dígitos. */
    private static String formatCep(String clean8Digits) {
        if (clean8Digits.length() == 8) {
            return clean8Digits.substring(0, 5) + "-" + clean8Digits.substring(5);
        }
        return clean8Digits;
    }

    // ── Payload interno ────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NominatimReverseResult(
            NominatimAddress address
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NominatimAddress(
            String postcode,
            String road,
            String suburb,
            String neighbourhood,
            String city,
            String town,
            String village,
            String state,
            @JsonProperty("state_code") String stateCodeRaw
    ) {
        /** Retorna o postcode limpo de caracteres não-numéricos. */
        String cleanPostcode() {
            if (postcode == null) return null;
            return postcode.replaceAll("\\D", "");
        }

        /**
         * Extrai a sigla da UF a partir do {@code state_code} ISO 3166-2:BR
         * (ex.: "BR-SP" → "SP", "SP" → "SP").
         */
        String stateCode() {
            if (stateCodeRaw == null) return null;
            // ISO 3166-2 devolve "BR-SP"; queremos apenas "SP".
            int dash = stateCodeRaw.lastIndexOf('-');
            return dash >= 0 ? stateCodeRaw.substring(dash + 1) : stateCodeRaw;
        }

        /** Bairro: prefere suburb, cai em neighbourhood se ausente. */
        String bairro() {
            if (suburb != null && !suburb.isBlank()) return suburb;
            return neighbourhood;
        }

        /** Localidade: prefere city, cai em town, depois village. */
        String localidade() {
            if (city != null && !city.isBlank()) return city;
            if (town != null && !town.isBlank()) return town;
            return village;
        }
    }
}
