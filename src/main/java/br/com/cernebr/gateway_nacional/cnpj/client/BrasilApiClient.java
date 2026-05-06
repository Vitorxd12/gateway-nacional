package br.com.cernebr.gateway_nacional.cnpj.client;

import br.com.cernebr.gateway_nacional.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Primary CNPJ provider — BrasilAPI (https://brasilapi.com.br).
 * Endpoint: /api/cnpj/v1/{cnpj}.
 */
@Slf4j
@Component("cnpjBrasilApiClient")
public class BrasilApiClient implements CnpjClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-CNPJ";

    private final RestClient restClient;

    public BrasilApiClient(RestClient.Builder builder,
                           @Value("${gateway.cnpj.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiCnpjCB", fallbackMethod = "fallback")
    public CnpjResponse fetch(String cnpj) {
        BrasilApiCnpjPayload payload = restClient.get()
                .uri("/api/cnpj/v1/{cnpj}", cnpj)
                .retrieve()
                .body(BrasilApiCnpjPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou resposta vazia ou CNPJ não localizado.");
        }
        return payload.toCnpjResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjResponse fallback(String cnpj, Throwable cause) {
        log.warn("BrasilAPI (CNPJ) fallback triggered for cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiCnpjPayload(
            String cnpj,
            @JsonProperty("razao_social") String razaoSocial,
            @JsonProperty("nome_fantasia") String nomeFantasia,
            @JsonProperty("cnae_fiscal") Long cnaeFiscal,
            @JsonProperty("descricao_situacao_cadastral") String descricaoSituacaoCadastral,
            String cep,
            String uf,
            String municipio
    ) {
        boolean isInvalid() {
            return cnpj == null || cnpj.isBlank();
        }

        CnpjResponse toCnpjResponse() {
            return new CnpjResponse(
                    cnpj,
                    razaoSocial,
                    nomeFantasia,
                    cnaeFiscal != null ? cnaeFiscal.toString() : null,
                    descricaoSituacaoCadastral,
                    cep,
                    uf,
                    municipio
            );
        }
    }
}
