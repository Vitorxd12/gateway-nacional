package br.com.cernebr.gateway_nacional.operacional.rastreio.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.operacional.rastreio.dto.EventoRastreio;
import br.com.cernebr.gateway_nacional.operacional.rastreio.dto.RastreioResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * Secondary tracking provider — BrasilAPI.
 *
 * <p>The base URL and path are configurable via
 * {@code gateway.rastreio.brasilapi.{base-url,path}}. Default path follows
 * the spec: {@code /api/corretoras/v1/{codigo}}. Production deployments may
 * point to a different upstream tracking endpoint without code changes.</p>
 *
 * <p>The expected payload schema follows the unified tracking shape
 * ({@code codigo}, {@code entregue}, {@code eventos[]}). Provider-specific
 * mapping is encapsulated in the private records below — extending the
 * Anti-Corruption Layer surface is a one-class change.</p>
 */
@Slf4j
@Component
public class BrasilApiRastreioClient implements RastreioClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Rastreio";

    private final RestClient restClient;
    private final String path;

    public BrasilApiRastreioClient(RestClient.Builder builder,
                                   @Value("${gateway.rastreio.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl,
                                   @Value("${gateway.rastreio.brasilapi.path:/api/corretoras/v1/{codigo}}") String path) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.path = path;
    }

    @Override
    @CircuitBreaker(name = "brasilApiRastreioCB", fallbackMethod = "fallback")
    public RastreioResponse fetch(String codigo) {
        BrasilApiPayload payload = restClient.get()
                .uri(path, codigo)
                .retrieve()
                .body(BrasilApiPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou resposta vazia ou código não localizado.");
        }
        return payload.toRastreioResponse(codigo);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private RastreioResponse fallback(String codigo, Throwable cause) {
        log.warn("BrasilAPI (Rastreio) fallback triggered for codigo={} cause={}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiPayload(
            String codigo,
            Boolean entregue,
            List<BrasilApiEvento> eventos
    ) {
        boolean isInvalid() {
            return eventos == null || eventos.isEmpty();
        }

        RastreioResponse toRastreioResponse(String requestedCodigo) {
            String canonical = (codigo != null ? codigo : requestedCodigo).toUpperCase(Locale.ROOT);
            List<EventoRastreio> ordered = eventos.stream()
                    .map(BrasilApiEvento::toEventoRastreio)
                    .sorted(Comparator.comparing(EventoRastreio::dataHora).reversed())
                    .toList();
            // Trust the upstream `entregue` flag if present; otherwise derive from events.
            boolean delivered = (entregue != null) ? entregue : detectDelivery(ordered);
            return new RastreioResponse(canonical, delivered, ordered);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiEvento(
            LocalDateTime dataHora,
            String local,
            String status,
            String descricao
    ) {
        EventoRastreio toEventoRastreio() {
            return new EventoRastreio(dataHora, local, status, descricao);
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
