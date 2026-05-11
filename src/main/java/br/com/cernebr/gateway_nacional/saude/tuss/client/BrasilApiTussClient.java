package br.com.cernebr.gateway_nacional.saude.tuss.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.tuss.dto.TussCodigoResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Tier 1 — proxy BrasilAPI {@code /api/tuss/v1/...}. A BrasilAPI já agrega o
 * dicionário TUSS oficial (RULE B — consolida a fonte ANS internamente) com
 * suporte a busca por nome, por prefixo e detalhe por código exato.
 */
@Slf4j
@Component
public class BrasilApiTussClient {

    public static final String PROVIDER_NAME = "BrasilAPI-TUSS";

    private final RestClient restClient;

    public BrasilApiTussClient(RestClient.Builder builder,
                               @Value("${gateway.tuss.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @CircuitBreaker(name = "tussBrasilApiCB", fallbackMethod = "fallbackList")
    public BrasilApiPage listAndSearch(String name, String tuss, Integer limit, Integer offset) {
        Function<UriBuilder, java.net.URI> uriBuilder = b -> {
            b.path("/api/tuss/v1");
            if (name != null && !name.isBlank()) b.queryParam("name", name);
            if (tuss != null && !tuss.isBlank()) b.queryParam("tuss", tuss);
            if (limit != null) b.queryParam("limit", limit);
            if (offset != null) b.queryParam("offset", offset);
            return b.build();
        };

        BrasilApiPage payload = restClient.get()
                .uri(uriBuilder)
                .retrieve()
                .body(BrasilApiPage.class);
        if (payload == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI TUSS devolveu corpo vazio (listAndSearch).");
        }
        return payload;
    }

    /**
     * Chama o endpoint dedicado de autocomplete da BrasilAPI
     * ({@code /api/tuss/v1/autocomplete}). Diferente de
     * {@link #listAndSearch(String, String, String, Integer, Integer)}, devolve
     * um <b>array plano</b> com apenas {@code {tuss, name}} — payload otimizado
     * para typeahead em UI hospitalar (campo "busque o procedimento" digitado
     * letra a letra, dezenas de requests por busca).
     *
     * <p>O upstream aplica {@code match='prefix'} e {@code sort='tuss asc'}
     * forçados; aqui só passamos os filtros e o limit.</p>
     */
    @CircuitBreaker(name = "tussBrasilApiCB", fallbackMethod = "fallbackAutocomplete")
    public List<TussCodigoResponse> autocomplete(String q, String name, String tuss, int limit) {
        Function<UriBuilder, java.net.URI> uriBuilder = b -> {
            b.path("/api/tuss/v1/autocomplete");
            if (q != null && !q.isBlank()) b.queryParam("q", q);
            if (name != null && !name.isBlank()) b.queryParam("name", name);
            if (tuss != null && !tuss.isBlank()) b.queryParam("tuss", tuss);
            b.queryParam("limit", limit);
            return b.build();
        };
        List<TussRow> body = restClient.get()
                .uri(uriBuilder)
                .retrieve()
                .body(TUSS_ROW_LIST);
        if (body == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI TUSS autocomplete devolveu corpo vazio.");
        }
        return body.stream()
                .map(r -> new TussCodigoResponse(r.tuss, r.name))
                .toList();
    }

    @CircuitBreaker(name = "tussBrasilApiCB", fallbackMethod = "fallbackDetail")
    public Optional<TussCodigoResponse> findByCode(String code) {
        try {
            TussRow row = restClient.get()
                    .uri("/api/tuss/v1/{code}", code)
                    .retrieve()
                    .body(TussRow.class);
            return Optional.ofNullable(row).map(r -> new TussCodigoResponse(r.tuss, r.name));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unused")
    private BrasilApiPage fallbackList(String name, String tuss, Integer limit, Integer offset, Throwable cause) {
        log.warn("BrasilAPI TUSS list fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI TUSS indisponível ou Circuit Breaker aberto (list).", cause);
    }

    @SuppressWarnings("unused")
    private Optional<TussCodigoResponse> fallbackDetail(String code, Throwable cause) {
        log.warn("BrasilAPI TUSS detail fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI TUSS indisponível ou Circuit Breaker aberto (detail).", cause);
    }

    @SuppressWarnings("unused")
    private List<TussCodigoResponse> fallbackAutocomplete(String q, String name, String tuss, int limit, Throwable cause) {
        log.warn("BrasilAPI TUSS autocomplete fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI TUSS indisponível ou Circuit Breaker aberto (autocomplete).", cause);
    }

    private static final ParameterizedTypeReference<List<TussRow>> TUSS_ROW_LIST =
            new ParameterizedTypeReference<>() {};

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BrasilApiPage(int total, Integer limit, int offset, List<TussRow> items) {
        public List<TussRow> items() { return items == null ? List.of() : items; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TussRow(String tuss, String name) {}
}
