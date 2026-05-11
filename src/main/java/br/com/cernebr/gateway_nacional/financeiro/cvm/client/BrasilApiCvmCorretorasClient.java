package br.com.cernebr.gateway_nacional.financeiro.cvm.client;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CorretoraResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CvmCorretorasSnapshot;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Provider FALLBACK de CVM Corretoras — BrasilAPI ({@code /api/cvm/corretoras/v1}).
 *
 * <p><b>Posição na cascata:</b> {@link CvmCorretorasClient} (download direto
 * do ZIP CVM + parse) é o tier 1. Este cliente entra em ação <em>somente</em>
 * quando o portal da CVM está instável durante o refresh do snapshot interno.</p>
 *
 * <p>BrasilAPI já entrega JSON cacheado no shape exato do nosso
 * {@link CorretoraResponse} (snake_case 1:1) — mapeamento direto via
 * desserialização Jackson, sem tradução manual de campos.</p>
 *
 * <p>Para a operação {@link #fetchSnapshot()}: BrasilAPI devolve a lista
 * inteira em uma chamada (são ~150 corretoras), barato.
 * Para {@link #findByCnpj(String)}: BrasilAPI tem endpoint dedicado
 * {@code /api/cvm/corretoras/v1/{cnpj}}, evita baixar lista inteira só
 * pra fazer um lookup.</p>
 */
@Slf4j
@Component
public class BrasilApiCvmCorretorasClient {

    public static final String PROVIDER_NAME = "BrasilAPI-CVM-Corretoras";
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");

    private static final ParameterizedTypeReference<List<CorretoraResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiCvmCorretorasClient(RestClient.Builder builder,
                                        @Value("${gateway.cvm.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "brasilApiCvmCorretorasCB", fallbackMethod = "fallbackSnapshot")
    public CvmCorretorasSnapshot fetchSnapshot() {
        log.debug("BrasilAPI CVM corretoras snapshot fetch (fallback)");
        List<CorretoraResponse> raw = restClient.get()
                .uri("/api/cvm/corretoras/v1")
                .retrieve()
                .body(LIST_TYPE);

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia para corretoras CVM.");
        }
        log.info("BrasilAPI CVM corretoras snapshot (fallback): {} corretoras", raw.size());
        return new CvmCorretorasSnapshot(raw, LocalDate.now(BR_ZONE));
    }

    @CircuitBreaker(name = "brasilApiCvmCorretorasCB", fallbackMethod = "fallbackByCnpj")
    public CorretoraResponse findByCnpj(String cnpj) {
        try {
            CorretoraResponse payload = restClient.get()
                    .uri("/api/cvm/corretoras/v1/{cnpj}", cnpj)
                    .retrieve()
                    .body(CorretoraResponse.class);
            if (payload == null || payload.cnpj() == null) {
                throw new ResourceNotFoundException("Corretora",
                        "Corretora CNPJ " + cnpj + " não localizada na BrasilAPI (fallback).");
            }
            return payload;
        } catch (HttpClientErrorException.NotFound nf) {
            throw new ResourceNotFoundException("Corretora",
                    "Corretora CNPJ " + cnpj + " não localizada na BrasilAPI (fallback).");
        }
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CvmCorretorasSnapshot fallbackSnapshot(Throwable cause) {
        log.warn("BrasilAPI CVM corretoras snapshot fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI CVM Corretoras indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private CorretoraResponse fallbackByCnpj(String cnpj, Throwable cause) {
        if (cause instanceof ResourceNotFoundException rnf) throw rnf;
        log.warn("BrasilAPI CVM corretoras findByCnpj fallback cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI CVM Corretoras indisponível ou Circuit Breaker aberto.", cause);
    }
}
