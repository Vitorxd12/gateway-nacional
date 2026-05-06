package br.com.cernebr.gateway_nacional.calendario.client;

import br.com.cernebr.gateway_nacional.calendario.dto.FeriadoResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Secondary holiday provider — Nager.Date (https://date.nager.at).
 *
 * <p>Uses {@code localName} (the Portuguese name) instead of {@code name}
 * (which is the English translation). Returns broader metadata which we
 * ignore via {@code @JsonIgnoreProperties}.</p>
 */
@Slf4j
@Component
public class NagerDateFeriadoClient implements FeriadoClientProvider {

    public static final String PROVIDER_NAME = "Nager.Date";

    private static final String DEFAULT_TIPO = "Nacional";
    private static final ParameterizedTypeReference<List<NagerDatePayload>> PAYLOAD_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public NagerDateFeriadoClient(RestClient.Builder builder,
                                  @Value("${gateway.calendario.nagerdate.base-url:https://date.nager.at}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "nagerDateCB", fallbackMethod = "fallback")
    public List<FeriadoResponse> fetch(int ano) {
        List<NagerDatePayload> payload = restClient.get()
                .uri("/api/v3/PublicHolidays/{ano}/BR", ano)
                .retrieve()
                .body(PAYLOAD_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Nager.Date retornou lista vazia de feriados para o ano solicitado.");
        }
        return payload.stream().map(NagerDatePayload::toFeriadoResponse).toList();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<FeriadoResponse> fallback(int ano, Throwable cause) {
        log.warn("Nager.Date fallback triggered for ano={} cause={}", ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Nager.Date indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NagerDatePayload(LocalDate date, String localName, String name) {
        FeriadoResponse toFeriadoResponse() {
            // Prefer localized Portuguese name; fall back to English if absent.
            String effectiveName = (localName != null && !localName.isBlank()) ? localName : name;
            return new FeriadoResponse(date, effectiveName, DEFAULT_TIPO);
        }
    }
}
