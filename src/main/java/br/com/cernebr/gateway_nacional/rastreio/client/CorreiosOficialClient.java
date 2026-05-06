package br.com.cernebr.gateway_nacional.rastreio.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.rastreio.dto.EventoRastreio;
import br.com.cernebr.gateway_nacional.rastreio.dto.RastreioResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Tertiary tracking provider — Correios oficial (placeholder).
 *
 * <p>The official Correios tracking service is gated behind a contract;
 * this client targets a configurable base URL and path so that customers
 * with the corporate credentials can plug their endpoint without code
 * changes. The default values point to a placeholder host that must be
 * overridden in production via
 * {@code gateway.rastreio.correios-oficial.{base-url,path}}.</p>
 */
@Slf4j
@Component
public class CorreiosOficialClient implements RastreioClientProvider {

    public static final String PROVIDER_NAME = "Correios-Oficial";

    private final RestClient restClient;
    private final String path;

    public CorreiosOficialClient(RestClient.Builder builder,
                                 @Value("${gateway.rastreio.correios-oficial.base-url:https://api.correios.com.br}") String baseUrl,
                                 @Value("${gateway.rastreio.correios-oficial.path:/srorastro/v1/objetos/{codigo}}") String path) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.path = path;
    }

    @Override
    @CircuitBreaker(name = "correiosOficialCB", fallbackMethod = "fallback")
    public RastreioResponse fetch(String codigo) {
        CorreiosPayload payload = restClient.get()
                .uri(path, codigo)
                .retrieve()
                .body(CorreiosPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Correios Oficial retornou resposta vazia ou código não localizado.");
        }
        return payload.toRastreioResponse(codigo);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private RastreioResponse fallback(String codigo, Throwable cause) {
        log.warn("Correios Oficial fallback triggered for codigo={} cause={}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Correios Oficial indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CorreiosPayload(
            @JsonProperty("trackingCode") String trackingCode,
            @JsonProperty("delivered") Boolean delivered,
            @JsonProperty("events") List<CorreiosEvento> events
    ) {
        boolean isInvalid() {
            return events == null || events.isEmpty();
        }

        RastreioResponse toRastreioResponse(String requestedCodigo) {
            String canonical = (trackingCode != null ? trackingCode : requestedCodigo).toUpperCase(Locale.ROOT);
            List<EventoRastreio> ordered = events.stream()
                    .map(CorreiosEvento::toEventoRastreio)
                    .sorted(Comparator.comparing(EventoRastreio::dataHora).reversed())
                    .toList();
            boolean isDelivered = (delivered != null) ? delivered : detectDelivery(ordered);
            return new RastreioResponse(canonical, isDelivered, ordered);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CorreiosEvento(
            @JsonProperty("timestamp") LocalDateTime timestamp,
            @JsonProperty("location") String location,
            @JsonProperty("status") String status,
            @JsonProperty("description") String description
    ) {
        EventoRastreio toEventoRastreio() {
            return new EventoRastreio(timestamp, location, status, description);
        }
    }

    private static boolean detectDelivery(List<EventoRastreio> events) {
        return events.stream()
                .map(EventoRastreio::status)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .anyMatch(s -> s.contains("ENTREG"));
    }
}
