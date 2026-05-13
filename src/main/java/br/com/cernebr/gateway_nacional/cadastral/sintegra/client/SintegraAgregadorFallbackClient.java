package br.com.cernebr.gateway_nacional.cadastral.sintegra.client;

import br.com.cernebr.gateway_nacional.cadastral.sintegra.dto.SintegraResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Fallback do Sintegra — agregador aberto (CNPJa / SintegraWS, conforme
 * disponibilidade) que cobre estados não suportados pelo CCC/SVRS (SP, MG, RJ)
 * e cenários de indisponibilidade do portal oficial.
 *
 * <p><b>Por que importa para o domínio:</b> sem este fallback, ~38% do PIB
 * brasileiro (SP/MG/RJ) ficaria descoberto sempre que o CCC oscilasse. A
 * latência média do agregador é ~1.4s contra ~600ms do CCC; ainda assim
 * o trade-off vale por cobertura.</p>
 *
 * <p><b>Contrato de erro:</b> retorna {@link Optional#empty()} para 404 do
 * agregador (IE não cadastrada para o CNPJ na UF), e lança
 * {@link ResourceUnavailableException} para 5xx/timeouts/rate-limit.</p>
 */
@Slf4j
@Component
public class SintegraAgregadorFallbackClient implements SintegraClient {

    public static final String PROVIDER_NAME = "Sintegra-Agregador";

    private static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale.Builder().setLanguage("pt").setRegion("BR").build());

    private final RestClient restClient;

    public SintegraAgregadorFallbackClient(
            RestClient.Builder builder,
            @Value("${gateway.sintegra.agregador.base-url:https://api.cnpja.com}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "sintegraAgregadorCB", fallbackMethod = "fallback")
    public Optional<SintegraResponse> fetch(String cnpj, String uf) {
        try {
            AgregadorPayload payload = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/sintegra/{cnpj}");
                        if (uf != null && !uf.isBlank()) {
                            uriBuilder.queryParam("uf", uf.toUpperCase(Locale.ROOT));
                        }
                        return uriBuilder.build(cnpj);
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { /* swallow → empty */ })
                    .body(AgregadorPayload.class);

            if (payload == null || payload.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(payload.toResponse(cnpj));

        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                return Optional.empty();
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Agregador devolveu " + ex.getStatusCode() + " para Sintegra " + cnpj + ".", ex);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Agregador inacessível: " + ex.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private Optional<SintegraResponse> fallback(String cnpj, String uf, Throwable cause) {
        log.warn("Sintegra agregador fallback for cnpj={} uf={} cause={}", cnpj, uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Agregador Sintegra indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AgregadorPayload(
            String ie,
            String uf,
            String situacao,
            @JsonProperty("data_situacao") String dataSituacao,
            String regime
    ) {
        boolean isEmpty() {
            return ie == null || ie.isBlank();
        }

        SintegraResponse toResponse(String cnpj) {
            return new SintegraResponse(
                    cnpj,
                    ie,
                    uf != null ? uf.toUpperCase(Locale.ROOT) : null,
                    normalizeSituacao(situacao),
                    normalizeDate(dataSituacao),
                    normalizeRegime(regime),
                    PROVIDER_NAME
            );
        }

        private static String normalizeSituacao(String raw) {
            if (raw == null) return null;
            String upper = raw.toUpperCase(Locale.ROOT);
            if (upper.contains("ATIV")) return "ATIVA";
            if (upper.contains("SUSPEN")) return "SUSPENSA";
            if (upper.contains("BAIX")) return "BAIXADA";
            if (upper.contains("INAPT")) return "INAPTA";
            if (upper.contains("NULA")) return "NULA";
            return upper;
        }

        private static String normalizeRegime(String raw) {
            if (raw == null) return null;
            String upper = raw.toUpperCase(Locale.ROOT);
            if (upper.contains("SIMPLES")) return "SIMPLES_NACIONAL";
            if (upper.contains("MEI")) return "MEI";
            if (upper.contains("ESTIM")) return "ESTIMATIVA";
            if (upper.contains("SUBSTITU")) return "SUBSTITUTO";
            if (upper.contains("NORMAL")) return "NORMAL";
            return upper;
        }

        private static String normalizeDate(String input) {
            if (input == null || input.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(input).toString();
            } catch (Exception ignored) {
                // Agregadores oscilam entre ISO e dd/MM/yyyy.
            }
            try {
                return LocalDate.parse(input, BR_DATE).toString();
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
