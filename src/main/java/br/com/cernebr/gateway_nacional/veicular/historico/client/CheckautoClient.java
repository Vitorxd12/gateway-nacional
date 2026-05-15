package br.com.cernebr.gateway_nacional.veicular.historico.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Premium vehicle-history provider — Checkauto (https://api.checkauto.com.br).
 *
 * <p>Second authenticated fast path of the hybrid orchestration, alongside
 * {@link OlhoNoCarroClient}. Checkauto authenticates via the {@code X-API-KEY}
 * header (vs. Olho no Carro's Bearer scheme) and returns a flat
 * {@code apontamentos[]} array — each element a single risk finding — plus a
 * top-level {@code risco} band. The Anti-Corruption Layer
 * ({@link CheckautoPayload}) folds that array into the unified
 * {@link HistoricoEvidencia}.</p>
 *
 * <p><b>Hybrid fallback contract:</b> when {@code gateway.historico.checkauto.api-key}
 * (env {@code GATEWAY_CHECKAUTO_KEY}) is absent, the client short-circuits
 * before the network call and throws {@link ResourceUnavailableException} so
 * the orchestrator drops the fonte and the free-tier scraper mesh carries
 * the request. Premium when keyed, resilient when not.</p>
 */
@Slf4j
@Component
public class CheckautoClient implements HistoricoScraperClient {

    public static final String PROVIDER_NAME = "Checkauto";

    private final RestClient restClient;
    private final String apiKey;

    public CheckautoClient(
            RestClient.Builder builder,
            @Value("${gateway.historico.checkauto.base-url:https://api.checkauto.com.br}") String baseUrl,
            @Value("${gateway.historico.checkauto.api-key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    @CircuitBreaker(name = "checkautoCB", fallbackMethod = "fallback")
    public HistoricoEvidencia consultar(String placa) {
        if (!isConfigured()) {
            log.info("Checkauto API key não configurada; pulando provider premium para placa={} "
                    + "(malha de scrapers gratuitos assume como fallback).", placa);
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Checkauto não configurado: defina GATEWAY_CHECKAUTO_KEY para ativar o histórico premium.");
        }

        CheckautoPayload payload = restClient.get()
                .uri("/v2/consulta/{placa}", placa)
                .header("X-API-KEY", apiKey)
                .header("Accept", "application/json")
                .retrieve()
                .body(CheckautoPayload.class);

        if (payload == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Checkauto retornou corpo vazio para a placa consultada.");
        }

        boolean indicioLeilao = payload.temLeilao();
        boolean indicioSinistro = payload.temSinistro();
        String detalhe = payload.detalheConsolidado();

        log.info("Checkauto premium placa={} indicioLeilao={} indicioSinistro={} risco={}",
                placa, indicioLeilao, indicioSinistro, payload.risco());
        return new HistoricoEvidencia(PROVIDER_NAME, indicioLeilao, indicioSinistro, detalhe);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private HistoricoEvidencia fallback(String placa, Throwable cause) {
        log.warn("Checkauto fallback triggered for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Checkauto indisponível, sem credenciais ou Circuit Breaker aberto.", cause);
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Anti-Corruption Layer for the Checkauto response envelope. Risk
     * findings arrive as a flat {@code apontamentos[]} array; each
     * {@link Apontamento} carries a {@code categoria} discriminator
     * ({@code LEILAO} / {@code SINISTRO}) the ACL switches on.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckautoPayload(
            String placa,
            String risco,
            @JsonProperty("score_risco") Integer scoreRisco,
            List<Apontamento> apontamentos
    ) {

        /** Single risk finding. {@code categoria} = LEILAO | SINISTRO | OUTRO. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Apontamento(
                @JsonProperty("categoria") String categoria,
                @JsonProperty("descricao") String descricao,
                @JsonProperty("data") String data,
                @JsonProperty("origem") String origem
        ) {
            boolean isLeilao() {
                return categoria != null && categoria.trim().equalsIgnoreCase("LEILAO");
            }

            boolean isSinistro() {
                return categoria != null && categoria.trim().equalsIgnoreCase("SINISTRO");
            }
        }

        private List<Apontamento> safeApontamentos() {
            return apontamentos != null ? apontamentos : List.of();
        }

        boolean temLeilao() {
            return safeApontamentos().stream().anyMatch(Apontamento::isLeilao);
        }

        boolean temSinistro() {
            return safeApontamentos().stream().anyMatch(Apontamento::isSinistro);
        }

        /**
         * Flattens every apontamento into a single audit line. Returns
         * {@code null} when the array is empty — "nada consta" on this fonte.
         */
        String detalheConsolidado() {
            List<String> partes = new ArrayList<>();
            for (Apontamento a : safeApontamentos()) {
                partes.add((nullToTraco(a.categoria())
                        + " " + nullToTraco(a.data())
                        + " - " + nullToTraco(a.descricao())
                        + " (" + nullToTraco(a.origem()) + ")").trim());
            }
            if (partes.isEmpty()) {
                return null;
            }
            if (risco != null && !risco.isBlank()) {
                partes.add("Risco Checkauto: " + risco
                        + (scoreRisco != null ? " (score " + scoreRisco + ")" : ""));
            }
            return String.join(" | ", partes);
        }

        private static String nullToTraco(String value) {
            return (value == null || value.isBlank()) ? "—" : value.trim();
        }
    }
}
