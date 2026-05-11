package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.MoedaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider direto do catálogo de moedas via OLINDA/BCB
 * ({@code /olinda/servico/PTAX/versao/v1/odata/Moedas}).
 *
 * <p>O endpoint é OData puro — mesma fonte canônica consumida pelo
 * {@link BcbMoedasCatalogService} para validar pares. A diferença aqui é o
 * formato de saída: devolve {@link MoedaResponse} já mapeado para o contrato
 * público do Gateway, não {@code Set<String>}.</p>
 */
@Slf4j
@Component
public class BcbOlindaMoedasClient implements MoedasCatalogClientProvider {

    public static final String PROVIDER_NAME = "BCB-OLINDA-Moedas";
    private static final String CATALOG_PATH = "/olinda/servico/PTAX/versao/v1/odata/Moedas";

    private final RestClient restClient;

    public BcbOlindaMoedasClient(RestClient.Builder builder,
                                 @Value("${gateway.cambio.bcb.base-url:https://olinda.bcb.gov.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "cambioMoedasBcbCB", fallbackMethod = "fallback")
    public List<MoedaResponse> listAll() {
        BcbMoedasPayload payload = restClient.get()
                .uri(uri -> uri.path(CATALOG_PATH)
                        .queryParam("$top", 100)
                        .queryParam("$format", "json")
                        .build())
                .retrieve()
                .body(BcbMoedasPayload.class);

        if (payload == null || payload.value() == null || payload.value().isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BCB /Moedas devolveu corpo vazio.");
        }

        List<MoedaResponse> out = new ArrayList<>(payload.value().size());
        for (BcbMoeda m : payload.value()) {
            if (m.simbolo() == null || m.simbolo().isBlank()) continue;
            out.add(new MoedaResponse(m.simbolo(), m.nomeFormatado(), m.tipoMoeda()));
        }
        return out;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<MoedaResponse> fallback(Throwable cause) {
        log.warn("BCB OLINDA /Moedas fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BCB OLINDA /Moedas indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BcbMoedasPayload(List<BcbMoeda> value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BcbMoeda(String simbolo, String nomeFormatado, String tipoMoeda) {
    }
}
