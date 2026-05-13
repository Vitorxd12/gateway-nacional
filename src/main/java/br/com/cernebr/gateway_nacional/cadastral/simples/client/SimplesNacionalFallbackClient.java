package br.com.cernebr.gateway_nacional.cadastral.simples.client;

import br.com.cernebr.gateway_nacional.cadastral.simples.dto.SimplesNacionalResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Fallback do Simples Nacional — reaproveita o endpoint público da
 * <a href="https://www.receitaws.com.br/v1/cnpj/{cnpj}">ReceitaWS</a>, que já
 * devolve os campos {@code opcao_pelo_simples}, {@code data_opcao_pelo_simples},
 * {@code opcao_pelo_mei} e {@code data_opcao_pelo_mei} no payload de CNPJ.
 *
 * <p><b>Por que existe um client dedicado ao invés de injetar
 * {@code ReceitaWsClient}:</b> o cliente de CNPJ está fortemente acoplado ao
 * {@code CnpjResponse} (campos como razão social, CNAE, situação) — expor os
 * campos de Simples por aquele caminho exigiria poluir o DTO de CNPJ ou
 * introduzir um getter "secundário". Aqui mantemos a integração isolada por
 * domínio: mesmo provedor, recortes diferentes, contratos diferentes.</p>
 *
 * <p><b>Trade-off conhecido:</b> a ReceitaWS aplica rate-limit agressivo no
 * tier gratuito (3 req/min). O Circuit Breaker {@code simplesFallbackCB} e o
 * cache de 12h no service mitigam o impacto. Em escala, considerar plano pago
 * ou self-host do MinhaReceita como segundo fallback.</p>
 */
@Slf4j
@Component
public class SimplesNacionalFallbackClient implements SimplesNacionalClientProvider {

    public static final String PROVIDER_NAME = "ReceitaWS-SimplesFallback";
    private static final String STATUS_ERROR = "ERROR";

    private static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale.Builder().setLanguage("pt").setRegion("BR").build());

    private final RestClient restClient;

    public SimplesNacionalFallbackClient(
            RestClient.Builder builder,
            @Value("${gateway.simples.receitaws.base-url:https://www.receitaws.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "simplesFallbackCB", fallbackMethod = "fallback")
    public SimplesNacionalResponse fetch(String cnpj) {
        ReceitaWsSimplesPayload payload = restClient.get()
                .uri("/v1/cnpj/{cnpj}", cnpj)
                .retrieve()
                .body(ReceitaWsSimplesPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ReceitaWS retornou resposta vazia, com erro ou CNPJ não localizado para consulta de Simples.");
        }
        return payload.toResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private SimplesNacionalResponse fallback(String cnpj, Throwable cause) {
        log.warn("Simples (ReceitaWS fallback) cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ReceitaWS indisponível, sob rate-limit ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReceitaWsSimplesPayload(
            String status,
            String cnpj,
            @JsonProperty("simples") OpcaoSimples simples,
            @JsonProperty("simei") OpcaoSimples simei
    ) {
        boolean isInvalid() {
            return STATUS_ERROR.equalsIgnoreCase(status)
                    || cnpj == null || cnpj.isBlank();
        }

        SimplesNacionalResponse toResponse() {
            boolean optanteSimples = simples != null && simples.optante();
            boolean optanteSimei = simei != null && simei.optante();
            return new SimplesNacionalResponse(
                    digitsOnly(cnpj),
                    optanteSimples,
                    optanteSimples && simples != null ? normalizeDate(simples.dataOpcao()) : null,
                    optanteSimei,
                    optanteSimei && simei != null ? normalizeDate(simei.dataOpcao()) : null,
                    PROVIDER_NAME
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpcaoSimples(
            boolean optante,
            @JsonProperty("data_opcao") String dataOpcao
    ) {
    }

    private static String digitsOnly(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replaceAll("\\D", "");
    }

    private static String normalizeDate(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(input).toString();
        } catch (Exception ignored) {
            // ReceitaWS oscila entre ISO-8601 e dd/MM/yyyy dependendo do plano.
        }
        try {
            return LocalDate.parse(input, BR_DATE).toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
