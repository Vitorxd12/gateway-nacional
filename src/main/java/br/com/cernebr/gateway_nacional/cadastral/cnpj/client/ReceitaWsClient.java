package br.com.cernebr.gateway_nacional.cadastral.cnpj.client;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Secondary CNPJ provider — ReceitaWS (https://www.receitaws.com.br).
 * Free tier rate-limits aggressively (HTTP 429), so the Circuit Breaker is
 * particularly important here. Field naming differs significantly from
 * BrasilAPI: "nome" vs "razao_social", "fantasia" vs "nome_fantasia",
 * "atividade_principal[].code" vs "cnae_fiscal".
 *
 * <p>ReceitaWS returns HTTP 200 with {@code {"status": "ERROR", "message": ...}}
 * on invalid CNPJs — handled by inspecting the {@code status} field rather
 * than relying on HTTP status codes.</p>
 */
@Slf4j
@Component
public class ReceitaWsClient implements CnpjClientProvider {

    public static final String PROVIDER_NAME = "ReceitaWS";
    private static final String STATUS_ERROR = "ERROR";

    private final RestClient restClient;

    public ReceitaWsClient(RestClient.Builder builder,
                           @Value("${gateway.cnpj.receitaws.base-url:https://www.receitaws.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "receitaWsCB", fallbackMethod = "fallback")
    public CnpjResponse fetch(String cnpj) {
        ReceitaWsPayload payload = restClient.get()
                .uri("/v1/cnpj/{cnpj}", cnpj)
                .retrieve()
                .body(ReceitaWsPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ReceitaWS retornou resposta vazia, com erro ou CNPJ não localizado.");
        }
        return payload.toCnpjResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjResponse fallback(String cnpj, Throwable cause) {
        log.warn("ReceitaWS fallback triggered for cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ReceitaWS indisponível, sob rate-limit ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReceitaWsPayload(
            String status,
            String cnpj,
            String nome,
            String fantasia,
            String situacao,
            String cep,
            String uf,
            String municipio,
            List<AtividadePrincipal> atividade_principal
    ) {
        boolean isInvalid() {
            return STATUS_ERROR.equalsIgnoreCase(status)
                    || cnpj == null || cnpj.isBlank();
        }

        CnpjResponse toCnpjResponse() {
            return new CnpjResponse(
                    digitsOnly(cnpj),
                    nome,
                    fantasia,
                    extractPrimaryCnae(),
                    situacao,
                    digitsOnly(cep),
                    uf,
                    municipio
            );
        }

        private String extractPrimaryCnae() {
            if (atividade_principal == null || atividade_principal.isEmpty()) {
                return null;
            }
            AtividadePrincipal primary = atividade_principal.get(0);
            return primary != null ? digitsOnly(primary.code()) : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AtividadePrincipal(String code, String text) {
    }

    private static String digitsOnly(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replaceAll("\\D", "");
    }
}
