package br.com.cernebr.gateway_nacional.cnpj.client;

import br.com.cernebr.gateway_nacional.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Tertiary CNPJ provider — MinhaReceita (https://minhareceita.org).
 * Endpoint: /{cnpj}. Schema mirrors the official Receita Federal CSV dump,
 * so field names align closely with BrasilAPI's CNPJ v1.
 */
@Slf4j
@Component
public class MinhaReceitaClient implements CnpjClientProvider {

    public static final String PROVIDER_NAME = "MinhaReceita";
    private static final String BASE_URL = "https://minhareceita.org";

    private final RestClient restClient;

    public MinhaReceitaClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    @Override
    @CircuitBreaker(name = "minhaReceitaCB", fallbackMethod = "fallback")
    public CnpjResponse fetch(String cnpj) {
        MinhaReceitaPayload payload = restClient.get()
                .uri("/{cnpj}", cnpj)
                .retrieve()
                .body(MinhaReceitaPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "MinhaReceita retornou resposta vazia ou CNPJ não localizado.");
        }
        return payload.toCnpjResponse();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjResponse fallback(String cnpj, Throwable cause) {
        log.warn("MinhaReceita fallback triggered for cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "MinhaReceita indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MinhaReceitaPayload(
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
