package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cliente para a AwesomeAPI de cotações em tempo real
 * (https://economia.awesomeapi.com.br).
 *
 * <p>O endpoint {@code /json/last/{pares}} aceita múltiplas moedas separadas
 * por vírgula (ex: {@code USD-BRL,EUR-BRL,BTC-BRL}) e devolve um objeto cujas
 * chaves são os pares concatenados sem hífen ({@code "USDBRL"}, {@code "EURBRL"}).
 * Como o conjunto é dinâmico, o parsing usa {@link Map} ao invés de uma classe
 * com campos fixos.</p>
 */
@Slf4j
@Component
public class CambioClient {

    public static final String PROVIDER_NAME = "AwesomeAPI-Cambio";

    private static final DateTimeFormatter CREATE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ParameterizedTypeReference<LinkedHashMap<String, AwesomeCambioPayload>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public CambioClient(RestClient.Builder builder,
                        @Value("${gateway.cambio.awesomeapi.base-url:https://economia.awesomeapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "awesomeApiCambioCB", fallbackMethod = "fallback")
    public List<CambioResponse> fetch(String pares) {
        String canonical = pares.toUpperCase(Locale.ROOT);
        log.debug("Cambio fetch upstream pares={}", canonical);

        Map<String, AwesomeCambioPayload> payload;
        try {
            payload = restClient.get()
                    .uri("/json/last/{pares}", canonical)
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (HttpClientErrorException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 404 || status.is4xxClientError()) {
                throw new ResourceNotFoundException("cambio",
                        "Par(es) de moedas não localizado(s) na AwesomeAPI: " + canonical);
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao consultar a AwesomeAPI: HTTP " + status.value(), ex);
        }

        if (payload == null || payload.isEmpty()) {
            throw new ResourceNotFoundException("cambio",
                    "Nenhuma cotação localizada para os pares: " + canonical);
        }

        return payload.values().stream()
                .map(AwesomeCambioPayload::toResponse)
                .toList();
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<CambioResponse> fallback(String pares, Throwable cause) {
        // O CB não deve mascarar 404 — é um resultado determinístico e não
        // significa indisponibilidade. Só repropagar; quem origina (try/catch
        // acima) já enviou um ResourceNotFoundException semanticamente correto.
        if (cause instanceof ResourceNotFoundException rnf) {
            throw rnf;
        }
        log.warn("AwesomeAPI Câmbio fallback acionado para pares={} cause={}", pares, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "AwesomeAPI de câmbio indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AwesomeCambioPayload(
            String code,
            String codein,
            String bid,
            String ask,
            String pctChange,
            String create_date
    ) {
        CambioResponse toResponse() {
            BigDecimal compra = parseDecimal(bid);
            BigDecimal venda = parseDecimal(ask);
            BigDecimal variacao = parseDecimal(pctChange);
            LocalDateTime dataHora = parseDateTime(create_date);
            return new CambioResponse(code, codein, compra, venda, variacao, dataHora);
        }

        private static BigDecimal parseDecimal(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static LocalDateTime parseDateTime(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return LocalDateTime.parse(raw, CREATE_DATE_FORMAT);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
