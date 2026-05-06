package br.com.cernebr.gateway_nacional.taxas.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.taxas.dto.TaxaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Secondary rate provider — Banco Central do Brasil, Sistema Gerenciador de
 * Séries Temporais (SGS). Endpoint:
 * {@code /dados/serie/bcdata.sgs.{codigo}/dados/ultimos/1?formato=json}.
 *
 * <p>SGS is series-coded rather than name-coded, so we keep an internal map
 * from canonical sigla to the official series number. The response payload is
 * a single-element JSON array of {@code {data, valor}} where:</p>
 *
 * <ul>
 *   <li>{@code data} arrives in {@code dd/MM/yyyy} (Brazilian format) — parsed manually.</li>
 *   <li>{@code valor} arrives as a numeric <em>string</em> (locale-safe) — parsed into {@link BigDecimal}.</li>
 * </ul>
 */
@Slf4j
@Component
public class BcbSgsTaxasClient implements TaxaClientProvider {

    public static final String PROVIDER_NAME = "BCB-SGS";

    private static final Map<String, Integer> SGS_CODE_BY_SIGLA = Map.of(
            "CDI",   12,
            "SELIC", 11,
            "IPCA",  433
    );

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ParameterizedTypeReference<List<BcbSgsPayload>> PAYLOAD_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BcbSgsTaxasClient(RestClient.Builder builder,
                             @Value("${gateway.taxas.bcb-sgs.base-url:https://api.bcb.gov.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "bcbSgsCB", fallbackMethod = "fallback")
    public TaxaResponse fetch(String sigla) {
        String canonical = sigla.toUpperCase(Locale.ROOT);
        Integer code = SGS_CODE_BY_SIGLA.get(canonical);
        if (code == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Sigla não suportada pelo BCB SGS: " + sigla);
        }

        List<BcbSgsPayload> payload = restClient.get()
                .uri("/dados/serie/bcdata.sgs.{codigo}/dados/ultimos/1?formato=json", code)
                .retrieve()
                .body(PAYLOAD_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BCB SGS retornou lista vazia para a série solicitada.");
        }

        BcbSgsPayload latest = payload.get(0);
        return new TaxaResponse(canonical, latest.parseValor(), latest.parseDate());
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private TaxaResponse fallback(String sigla, Throwable cause) {
        log.warn("BCB SGS fallback triggered for sigla={} cause={}", sigla, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BCB SGS indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BcbSgsPayload(String data, String valor) {
        LocalDate parseDate() {
            return LocalDate.parse(data, BR_DATE);
        }

        BigDecimal parseValor() {
            return new BigDecimal(valor);
        }
    }
}
