package br.com.cernebr.gateway_nacional.financeiro.cvm.client;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundoDetailResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundoSummaryResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundosPageResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Provider FALLBACK de CVM Fundos — BrasilAPI ({@code /api/cvm/fundos/v1}).
 *
 * <p><b>Posição na cascata:</b> {@link CvmFundosClient} (download direto do
 * {@code cad_fi.csv} ~10MB) é o tier 1. Este cliente entra apenas quando o
 * portal CVM está fora durante o refresh do snapshot.</p>
 *
 * <p><b>Granularidade do fallback:</b> diferente do CVM Corretoras, a base
 * de fundos tem ~30k registros — replicar o snapshot completo via paginação
 * BrasilAPI seria 150+ requests. Por isso a cascata aqui é
 * <b>operation-level</b>:</p>
 * <ul>
 *   <li>{@code listPaginated} → BrasilAPI {@code /api/cvm/fundos/v1?size=N&page=P}
 *       (suporta paginação nativa)</li>
 *   <li>{@code findByCnpj} → BrasilAPI {@code /api/cvm/fundos/v1/{cnpj}}
 *       (endpoint dedicado por CNPJ, evita download em lote)</li>
 * </ul>
 *
 * <p>Modo degradado aceitável: enquanto a CVM está fora, perdemos a
 * facilidade de "lookup por CNPJ instantâneo via snapshot" e cada request
 * vira um round-trip à BrasilAPI. Latência maior, mas o serviço continua
 * respondendo.</p>
 */
@Slf4j
@Component
public class BrasilApiCvmFundosClient {

    public static final String PROVIDER_NAME = "BrasilAPI-CVM-Fundos";

    private final RestClient restClient;

    public BrasilApiCvmFundosClient(RestClient.Builder builder,
                                    @Value("${gateway.cvm.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "brasilApiCvmFundosCB", fallbackMethod = "fallbackPage")
    public FundosPageResponse fetchPage(int size, int page) {
        BrasilApiFundosPagePayload payload = restClient.get()
                .uri(uri -> uri.path("/api/cvm/fundos/v1")
                        .queryParam("size", size)
                        .queryParam("page", page)
                        .build())
                .retrieve()
                .body(BrasilApiFundosPagePayload.class);

        if (payload == null || payload.data() == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI CVM fundos retornou corpo vazio para size=" + size + " page=" + page);
        }
        // BrasilAPI não devolve totalRecords nesse endpoint; passamos -1 pra
        // sinalizar "desconhecido" sem quebrar o contrato (consumidores
        // espertos lidam com -1 fazendo paginação por enquanto-tem-data).
        return new FundosPageResponse(size, page, -1, payload.data());
    }

    @CircuitBreaker(name = "brasilApiCvmFundosCB", fallbackMethod = "fallbackByCnpj")
    public FundoDetailResponse findByCnpj(String cnpj) {
        try {
            FundoDetailResponse payload = restClient.get()
                    .uri("/api/cvm/fundos/v1/{cnpj}", cnpj)
                    .retrieve()
                    .body(FundoDetailResponse.class);
            if (payload == null || payload.cnpj() == null) {
                throw new ResourceNotFoundException("Fundo",
                        "Fundo CNPJ " + cnpj + " não localizado na BrasilAPI (fallback).");
            }
            return payload;
        } catch (HttpClientErrorException.NotFound nf) {
            throw new ResourceNotFoundException("Fundo",
                    "Fundo CNPJ " + cnpj + " não localizado na BrasilAPI (fallback).");
        }
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private FundosPageResponse fallbackPage(int size, int page, Throwable cause) {
        log.warn("BrasilAPI CVM fundos page fallback acionado size={} page={} cause={}",
                size, page, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI CVM Fundos indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private FundoDetailResponse fallbackByCnpj(String cnpj, Throwable cause) {
        if (cause instanceof ResourceNotFoundException rnf) throw rnf;
        log.warn("BrasilAPI CVM fundos findByCnpj fallback cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI CVM Fundos indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiFundosPagePayload(List<FundoSummaryResponse> data, Integer page, Integer size) {
    }
}
