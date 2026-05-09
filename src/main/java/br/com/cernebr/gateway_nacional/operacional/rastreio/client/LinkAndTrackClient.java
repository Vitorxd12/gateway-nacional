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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Primary tracking provider — Link&amp;Track (https://linketrack.com).
 *
 * <p>Authentication is via {@code user} + {@code token} query parameters.
 * Default credentials use the public test pair documented by the provider —
 * production deployments should override
 * {@code gateway.rastreio.linketrack.{user,token}} with paid credentials for
 * higher throughput and SLA.</p>
 *
 * <p>Date and time arrive split (Brazilian formats {@code dd/MM/yyyy} and
 * {@code HH:mm}) and are merged into {@link LocalDateTime} inside the ACL.</p>
 */
@Slf4j
@Component
public class LinkAndTrackClient implements RastreioClientProvider {

    public static final String PROVIDER_NAME = "LinkAndTrack";

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter BR_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final RestClient restClient;
    private final String user;
    private final String token;

    public LinkAndTrackClient(RestClient.Builder builder,
                              @Value("${gateway.rastreio.linketrack.base-url:https://api.linketrack.com}") String baseUrl,
                              @Value("${gateway.rastreio.linketrack.user:teste}") String user,
                              @Value("${gateway.rastreio.linketrack.token:1abcd00b2731640e886fb41a8a9671ad1434c599dbaa0a0de9a5aa619f29a83f}") String token) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.user = user;
        this.token = token;
    }

    @Override
    @CircuitBreaker(name = "linkAndTrackCB", fallbackMethod = "fallback")
    public RastreioResponse fetch(String codigo) {
        LinkAndTrackPayload payload = restClient.get()
                .uri("/track/json?user={user}&token={token}&codigo={codigo}", user, token, codigo)
                .retrieve()
                .body(LinkAndTrackPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Link&Track retornou resposta vazia ou código não localizado.");
        }
        return payload.toRastreioResponse(codigo);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private RastreioResponse fallback(String codigo, Throwable cause) {
        log.warn("Link&Track fallback triggered for codigo={} cause={}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Link&Track indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LinkAndTrackPayload(
            String codigo,
            Integer quantidade,
            String servico,
            List<LinkAndTrackEvento> eventos
    ) {
        boolean isInvalid() {
            return eventos == null || eventos.isEmpty();
        }

        RastreioResponse toRastreioResponse(String requestedCodigo) {
            String canonical = (codigo != null ? codigo : requestedCodigo).toUpperCase(Locale.ROOT);
            List<EventoRastreio> ordered = eventos.stream()
                    .map(LinkAndTrackEvento::toEventoRastreio)
                    .sorted(Comparator.comparing(EventoRastreio::dataHora).reversed())
                    .toList();
            return new RastreioResponse(canonical, detectDelivery(ordered), ordered);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LinkAndTrackEvento(
            String data,
            String hora,
            String local,
            String status,
            String subStatus
    ) {
        EventoRastreio toEventoRastreio() {
            LocalDateTime when = LocalDateTime.of(
                    LocalDate.parse(data, BR_DATE),
                    LocalTime.parse(hora, BR_TIME)
            );
            return new EventoRastreio(when, local, status, subStatus);
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
