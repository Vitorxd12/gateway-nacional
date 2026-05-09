package br.com.cernebr.gateway_nacional.juridico.processos.client;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.juridico.processos.dto.ProcessoResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cliente da API Pública DataJud do CNJ.
 *
 * <p><b>Desvio intencional do brief:</b> o brief mencionou um endpoint REST
 * {@code GET /api_publica_cnj/v1/processos/{numero}} — esse formato não existe.
 * A API real é {@code POST /api_publica_{alias}/_search} com body Elasticsearch
 * DSL e header {@code Authorization: APIKey {chave-pública}}. A chave do CNJ
 * é pública (documentada no portal), por isso vem com default razoável e
 * pode ser sobrescrita via env. O alias é resolvido a partir do
 * numeroProcesso pelo {@link TribunalResolver}.</p>
 *
 * <p>O endpoint {@code /_search} retorna estrutura Elasticsearch padrão
 * ({@code hits.hits[].source}). Como filtramos por numeroProcesso (campo
 * único na base), a expectativa é 0 ou 1 hit.</p>
 */
@Slf4j
@Component
public class ProcessosClient {

    public static final String PROVIDER_NAME = "DataJud-CNJ";

    private final RestClient restClient;
    private final TribunalResolver tribunalResolver;
    private final String apiKey;

    public ProcessosClient(RestClient.Builder builder,
                           TribunalResolver tribunalResolver,
                           @Value("${gateway.juridico.processos.datajud.base-url:https://api-publica.datajud.cnj.jus.br}") String baseUrl,
                           // Chave pública documentada pelo CNJ — overridable em produção
                           // via env caso o CNJ rode rotação de credenciais.
                           @Value("${gateway.juridico.processos.datajud.api-key:cDZHYzlZa0JadVREZDJCendQbXY6SkJlTzNjLV9TRENyQk1RdnFKZGRQdw==}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tribunalResolver = tribunalResolver;
        this.apiKey = apiKey;
    }

    @CircuitBreaker(name = "datajudCB", fallbackMethod = "fallback")
    public ProcessoResponse fetchByNumero(String numeroProcesso) {
        TribunalResolver.Resolved tribunal = tribunalResolver.resolve(numeroProcesso);
        log.debug("DataJud lookup numero={} alias={}", numeroProcesso, tribunal.alias());

        Map<String, Object> body = Map.of(
                "size", 1,
                "query", Map.of("match", Map.of("numeroProcesso", numeroProcesso))
        );

        EsResponse response = restClient.post()
                .uri("/{alias}/_search", tribunal.alias())
                .header("Authorization", "APIKey " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(EsResponse.class);

        if (response == null || response.hits() == null || response.hits().hits() == null
                || response.hits().hits().isEmpty()) {
            throw new ResourceNotFoundException("processo",
                    "Processo " + numeroProcesso + " não localizado no DataJud (" + tribunal.sigla() + ").");
        }

        EsHit hit = response.hits().hits().get(0);
        return hit.source().toResponse(numeroProcesso, tribunal.sigla());
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private ProcessoResponse fallback(String numeroProcesso, Throwable cause) {
        if (cause instanceof ResourceNotFoundException rnf) {
            throw rnf;
        }
        log.warn("DataJud fallback numero={} cause={}", numeroProcesso, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "API DataJud do CNJ indisponível ou Circuit Breaker aberto.", cause);
    }

    /* ------------ Estruturas de resposta Elasticsearch ------------ */

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsResponse(EsHits hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsHits(List<EsHit> hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsHit(@JsonProperty("_source") EsSource source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsSource(
            @JsonProperty("classe") EsClasse classe,
            @JsonProperty("assuntos") List<EsAssunto> assuntos,
            @JsonProperty("dataAjuizamento") String dataAjuizamento,
            @JsonProperty("movimentos") List<EsMovimento> movimentos
    ) {
        ProcessoResponse toResponse(String numeroProcesso, String tribunalSigla) {
            return new ProcessoResponse(
                    numeroProcesso,
                    tribunalSigla,
                    classe != null ? classe.nome() : null,
                    (assuntos != null && !assuntos.isEmpty() && assuntos.get(0) != null) ? assuntos.get(0).nome() : null,
                    parseDataAjuizamento(),
                    ultimoMovimento()
            );
        }

        private LocalDate parseDataAjuizamento() {
            if (dataAjuizamento == null || dataAjuizamento.isBlank()) return null;
            try {
                // DataJud emite ISO-8601 com offset (ex: 2024-08-13T12:34:56.000Z).
                return OffsetDateTime.parse(dataAjuizamento).toLocalDate();
            } catch (Exception ex) {
                try {
                    return LocalDate.parse(dataAjuizamento);
                } catch (Exception inner) {
                    return null;
                }
            }
        }

        private String ultimoMovimento() {
            if (movimentos == null || movimentos.isEmpty()) return null;
            EsMovimento ultimo = movimentos.get(movimentos.size() - 1);
            return ultimo == null ? null : ultimo.nome();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsClasse(@JsonProperty("nome") String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsAssunto(@JsonProperty("nome") String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsMovimento(@JsonProperty("nome") String nome) {
    }
}
