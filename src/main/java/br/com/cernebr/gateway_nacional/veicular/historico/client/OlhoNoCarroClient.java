package br.com.cernebr.gateway_nacional.veicular.historico.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Premium vehicle-history provider — Olho no Carro (https://api.olhonocarro.com.br).
 *
 * <p>This is the <b>fast path</b> of the hybrid orchestration. When the
 * {@code gateway.historico.olhonocarro.api-key} property is set (sourced from
 * the {@code GATEWAY_OLHONOCARRO_KEY} env var), the gateway resolves the
 * historico in a single authenticated REST round-trip (millisecond latency,
 * structured JSON — no Cloudflare, no Jsoup, no FlareSolverr handshake).</p>
 *
 * <p><b>Hybrid fallback contract:</b> when the key is absent the client
 * <b>short-circuits before the network call</b> (same posture as
 * {@code KeplacaClient}) and throws {@link ResourceUnavailableException}. The
 * {@code HistoricoService} orchestrator absorbs the throw, drops this fonte
 * from {@code fontesConsultadas}, and the free-tier scraper mesh
 * (LeilaoFree / ConsultarPlaca / PlacaFipe baseline) carries the request as
 * the resilience fallback. Zero config = still works, just slower.</p>
 *
 * <p>The Anti-Corruption Layer ({@link OlhoNoCarroPayload}) maps the
 * provider's nested {@code sinistro} / {@code leilao} / {@code score}
 * objects onto the unified {@link HistoricoEvidencia} the orchestrator
 * consolidates.</p>
 */
@Slf4j
@Component
public class OlhoNoCarroClient implements HistoricoScraperClient {

    public static final String PROVIDER_NAME = "OlhoNoCarro";

    private final RestClient restClient;
    private final String apiKey;

    public OlhoNoCarroClient(
            RestClient.Builder builder,
            @Value("${gateway.historico.olhonocarro.base-url:https://api.olhonocarro.com.br}") String baseUrl,
            @Value("${gateway.historico.olhonocarro.api-key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    @CircuitBreaker(name = "olhoNoCarroCB", fallbackMethod = "fallback")
    public HistoricoEvidencia consultar(String placa) {
        if (!isConfigured()) {
            log.info("OlhoNoCarro API key não configurada; pulando provider premium para placa={} "
                    + "(malha de scrapers gratuitos assume como fallback).", placa);
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "OlhoNoCarro não configurado: defina GATEWAY_OLHONOCARRO_KEY para ativar o histórico premium.");
        }

        OlhoNoCarroPayload payload = restClient.get()
                .uri("/v1/historico/{placa}", placa)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .retrieve()
                .body(OlhoNoCarroPayload.class);

        if (payload == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "OlhoNoCarro retornou corpo vazio para a placa consultada.");
        }

        boolean indicioLeilao = payload.temLeilao();
        boolean indicioSinistro = payload.temSinistro();
        String detalhe = payload.detalheConsolidado();

        log.info("OlhoNoCarro premium placa={} indicioLeilao={} indicioSinistro={} score={}",
                placa, indicioLeilao, indicioSinistro, payload.scoreRiscoBruto());
        return new HistoricoEvidencia(PROVIDER_NAME, indicioLeilao, indicioSinistro, detalhe);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private HistoricoEvidencia fallback(String placa, Throwable cause) {
        log.warn("OlhoNoCarro fallback triggered for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "OlhoNoCarro indisponível, sem credenciais ou Circuit Breaker aberto.", cause);
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Anti-Corruption Layer for the Olho no Carro response envelope. The
     * provider publishes three independent blocks — {@code sinistro},
     * {@code leilao} and {@code score} — each typed below so a schema drift
     * on one block does not corrupt the others.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OlhoNoCarroPayload(
            String placa,
            Sinistro sinistro,
            Leilao leilao,
            ScoreRisco score
    ) {

        /** Sinistro / insurance-claim block (perda total, salvado, indenização). */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Sinistro(
                @JsonProperty("possui_registro") boolean possuiRegistro,
                @JsonProperty("tipo") String tipo,
                @JsonProperty("data_ocorrencia") String dataOcorrencia,
                @JsonProperty("seguradora") String seguradora
        ) {
        }

        /** Leilão block (casa de leilão, data, lote, classificação do dano). */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Leilao(
                @JsonProperty("possui_registro") boolean possuiRegistro,
                @JsonProperty("comitente") String comitente,
                @JsonProperty("data_leilao") String dataLeilao,
                @JsonProperty("classificacao") String classificacao
        ) {
        }

        /** Score block — provider's own 0-1000 risk rating + textual band. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ScoreRisco(
                @JsonProperty("valor") Integer valor,
                @JsonProperty("classificacao") String classificacao
        ) {
        }

        boolean temSinistro() {
            return sinistro != null && sinistro.possuiRegistro();
        }

        boolean temLeilao() {
            return leilao != null && leilao.possuiRegistro();
        }

        Integer scoreRiscoBruto() {
            return score != null ? score.valor() : null;
        }

        /**
         * Builds the human-readable audit line the orchestrator stores in
         * {@code detalhesLeilao}. Returns {@code null} when no risk block
         * fired — keeps the consolidated detalhe clean for "nada consta".
         */
        String detalheConsolidado() {
            List<String> partes = new java.util.ArrayList<>(3);
            if (temLeilao()) {
                partes.add(("Leilão " + nullToTraco(leilao.comitente())
                        + " " + nullToTraco(leilao.dataLeilao())
                        + " - " + nullToTraco(leilao.classificacao())).trim());
            }
            if (temSinistro()) {
                partes.add(("Sinistro " + nullToTraco(sinistro.tipo())
                        + " " + nullToTraco(sinistro.dataOcorrencia())
                        + " - " + nullToTraco(sinistro.seguradora())).trim());
            }
            if (score != null && score.valor() != null) {
                partes.add("Score de risco " + score.valor()
                        + " (" + nullToTraco(score.classificacao()) + ")");
            }
            return partes.isEmpty() ? null : String.join(" | ", partes);
        }

        private static String nullToTraco(String value) {
            return (value == null || value.isBlank()) ? "—" : value.trim();
        }
    }
}
