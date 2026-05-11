package br.com.cernebr.gateway_nacional.cadastral.ibge.client;

import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfDetailResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Provider FALLBACK de IBGE — BrasilAPI ({@code /api/ibge/uf/v1*} e
 * {@code /api/ibge/municipios/v1/{uf}}).
 *
 * <p><b>Posição na cascata:</b> os providers diretos
 * ({@link IbgeGovClient} para tudo + {@link DadosAbertosBrClient} no hedge
 * de municípios) são o tier 1. Este cliente entra apenas quando o tier 1
 * inteiro falha.</p>
 *
 * <p><b>Bônus do fallback de UF:</b> a BrasilAPI já retorna o objeto de UF
 * <em>com</em> a estimativa populacional ({@code populacao_estimada} +
 * {@code periodo}) numa única chamada — quando este fallback resolve o
 * detalhe de UF, evitamos a chamada extra ao agregado de população.</p>
 */
@Slf4j
@Component
public class BrasilApiIbgeClient {

    public static final String PROVIDER_NAME = "BrasilAPI-IBGE";

    private static final ParameterizedTypeReference<List<BrasilApiUfPayload>> UFS_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<BrasilApiMunicipioPayload>> MUNICIPIOS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiIbgeClient(RestClient.Builder builder,
                               @Value("${gateway.ibge.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "brasilApiIbgeCB", fallbackMethod = "fallbackListUfs")
    public List<UfResponse> listAllUfs() {
        log.debug("BrasilAPI IBGE listAllUfs (fallback)");
        List<BrasilApiUfPayload> raw = restClient.get()
                .uri("/api/ibge/uf/v1")
                .retrieve()
                .body(UFS_TYPE);

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI IBGE retornou lista vazia para UFs.");
        }
        return raw.stream().map(BrasilApiUfPayload::toUfResponse).toList();
    }

    /**
     * BrasilAPI já agrega UF + população em uma única chamada — devolvemos
     * direto o {@link UfDetailResponse} (sem precisar de chamada extra ao
     * agregado de população).
     */
    @CircuitBreaker(name = "brasilApiIbgeCB", fallbackMethod = "fallbackFindUf")
    public UfDetailResponse findUfDetail(String codeOrSigla) {
        try {
            BrasilApiUfPayload raw = restClient.get()
                    .uri("/api/ibge/uf/v1/{code}", codeOrSigla)
                    .retrieve()
                    .body(BrasilApiUfPayload.class);
            if (raw == null || raw.id() == null) {
                throw new ResourceNotFoundException("UF",
                        "UF " + codeOrSigla + " não localizada na BrasilAPI (fallback).");
            }
            return raw.toUfDetailResponse();
        } catch (HttpClientErrorException.NotFound nf) {
            throw new ResourceNotFoundException("UF",
                    "UF " + codeOrSigla + " não localizada na BrasilAPI (fallback).");
        }
    }

    @CircuitBreaker(name = "brasilApiIbgeCB", fallbackMethod = "fallbackMunicipios")
    public List<MunicipioResponse> listMunicipiosByUf(String siglaUf) {
        try {
            List<BrasilApiMunicipioPayload> raw = restClient.get()
                    .uri("/api/ibge/municipios/v1/{uf}", siglaUf)
                    .retrieve()
                    .body(MUNICIPIOS_TYPE);

            if (raw == null || raw.isEmpty()) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "BrasilAPI IBGE retornou lista vazia para municípios da UF " + siglaUf);
            }
            return raw.stream()
                    .map(p -> new MunicipioResponse(p.nome(), p.codigoIbge()))
                    .toList();
        } catch (HttpClientErrorException.NotFound nf) {
            throw new ResourceNotFoundException("UF",
                    "UF " + siglaUf + " não localizada na BrasilAPI (fallback de municípios).");
        }
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<UfResponse> fallbackListUfs(Throwable cause) {
        log.warn("BrasilAPI IBGE listAllUfs fallback: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI IBGE indisponível ou Circuit Breaker aberto (UFs).", cause);
    }

    @SuppressWarnings("unused")
    private UfDetailResponse fallbackFindUf(String codeOrSigla, Throwable cause) {
        if (cause instanceof ResourceNotFoundException rnf) throw rnf;
        log.warn("BrasilAPI IBGE findUf fallback code={} cause={}", codeOrSigla, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI IBGE indisponível ou Circuit Breaker aberto (findUf).", cause);
    }

    @SuppressWarnings("unused")
    private List<MunicipioResponse> fallbackMunicipios(String siglaUf, Throwable cause) {
        if (cause instanceof ResourceNotFoundException rnf) throw rnf;
        log.warn("BrasilAPI IBGE municipios fallback uf={} cause={}", siglaUf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI IBGE indisponível ou Circuit Breaker aberto (municípios).", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiUfPayload(
            Integer id,
            String sigla,
            String nome,
            @JsonProperty("regiao_sigla") String regiaoSigla,
            @JsonProperty("regiao_nome") String regiaoNome,
            String capital,
            @JsonProperty("populacao_estimada") Long populacaoEstimada,
            String periodo
    ) {
        UfResponse toUfResponse() {
            return new UfResponse(id, sigla, nome, regiaoSigla, regiaoNome, capital);
        }

        UfDetailResponse toUfDetailResponse() {
            return new UfDetailResponse(id, sigla, nome, regiaoSigla, regiaoNome, capital,
                    populacaoEstimada, periodo);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiMunicipioPayload(
            String nome,
            @JsonProperty("codigo_ibge") String codigoIbge
    ) {
    }
}
