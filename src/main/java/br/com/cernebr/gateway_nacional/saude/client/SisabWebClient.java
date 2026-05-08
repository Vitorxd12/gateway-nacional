package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.dto.ProducaoSisabResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SISAB client — delegates the actual scrape to the {@code sisab-sidecar}
 * Python service (Selenium + headless Chromium).
 *
 * <h2>Why a Python sidecar instead of HTTP-pure</h2>
 * <p>The SISAB validation page is a JSF/Mojarra application whose filter
 * controls (validacao, colunas, competencia) are rendered by Bootstrap
 * Multiselect plugins. Those plugins keep their selection state in client-
 * side DOM and only sync back to the underlying {@code <select multiple>}
 * when their JavaScript fires on a real DOM click. Submitting via a JVM
 * HTTP client therefore arrives at the backend with empty selects, and
 * the JSF backend trips on {@code java.lang.IndexOutOfBoundsException}
 * trying to read the first element of an empty list. We validated this
 * empirically on 2026-05 — see issue #2 for the full session.</p>
 *
 * <p>Since FlareSolverr v3 does not expose JS interaction primitives
 * (no {@code executeJS}, no click), we adopted the pragmatic path: a
 * dedicated Python sidecar that owns the browser and exposes
 * {@code GET /scrape?ibge=...&competencia=...}. The Python implementation
 * reuses the proven-in-production logic from the upstream
 * {@code AutoAPSFinancias} project. From the Java gateway's perspective,
 * the sidecar is just another upstream provider with its own circuit
 * breaker.</p>
 *
 * <h2>Toggle de resiliência</h2>
 * <p>Quando {@code gateway.sisab-sidecar.url} é vazio (default), o cliente
 * <b>nem instancia o {@link RestClient}</b> e qualquer chamada lança
 * {@link ResourceUnavailableException} com a mensagem canônica orientando
 * o operador a ativar o sidecar — fast-fail, sem timeout inútil, sem
 * tentar URL inválida.</p>
 *
 * <h2>HTTP/1.1 forçado</h2>
 * <p>O cliente fixa explicitamente HTTP/1.1 no {@link JdkClientHttpRequestFactory}
 * porque o JDK {@code java.net.http.HttpClient} default tenta upgrade
 * {@code h2c} em conexões cleartext, e o uvicorn do sidecar fala apenas
 * HTTP/1.1 — o handshake corrompe o stream e o servidor lê body vazio
 * mesmo com {@code Content-Length} correto. Validado empiricamente em
 * 2026-05-08.</p>
 *
 * <h2>Latência e timeout</h2>
 * <p>Cold scrapes routinely take 30–90 s under headless Chromium. The
 * {@code @CircuitBreaker} time-limiter for {@code sisabScraperCB} should
 * therefore be set to at least 120 s — see {@code application.yml}.</p>
 */
@Slf4j
@Component
public class SisabWebClient implements SisabClientProvider {

    public static final String PROVIDER_NAME = "SISAB";
    static final String SIDECAR_REQUIRED_MESSAGE =
            "Esta rota exige a ativação do sidecar Python (sisab-sidecar) devido à navegação JSF complexa exigida pelo Governo.";

    private static final String SCRAPE_PATH = "/scrape";

    private final RestClient sidecarClient;
    private final boolean enabled;

    public SisabWebClient(RestClient.Builder builder,
                          @Value("${gateway.sisab-sidecar.url:}") String sidecarUrl) {
        this.enabled = sidecarUrl != null && !sidecarUrl.isBlank();
        this.sidecarClient = enabled ? buildHttp1Client(builder, sidecarUrl) : null;
        if (enabled) {
            log.info("SISAB sidecar configured at {}", sidecarUrl);
        }
    }

    /**
     * Builds a {@link RestClient} pinned to HTTP/1.1 for talking to the
     * Python/uvicorn sidecar.
     *
     * <p>The JDK's default {@code java.net.http.HttpClient} (which Spring
     * RestClient uses under the hood) negotiates HTTP/2 by default and emits
     * {@code Upgrade: h2c} + {@code HTTP2-Settings: ...} on cleartext.
     * Uvicorn does not speak HTTP/2 — it returns HTTP/1.1 but the upgrade
     * dance corrupts the request stream and uvicorn reads an empty body
     * even with a valid {@code Content-Length}. Forcing HTTP/1.1 at the
     * JDK client side eliminates the upgrade preamble entirely. Other
     * clients in this gateway (FlareSolverr, FIPE) keep the default
     * factory because their servers handle HTTP/2 negotiation properly.</p>
     */
    private static RestClient buildHttp1Client(RestClient.Builder builder, String baseUrl) {
        HttpClient http1 = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return builder
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(http1))
                .build();
    }

    @Override
    @CircuitBreaker(name = "sisabScraperCB", fallbackMethod = "fallback")
    public List<ProducaoSisabResponse> fetchProducao(String ibge6, int ano, int mes) {
        if (!enabled) {
            throw new ResourceUnavailableException(PROVIDER_NAME, SIDECAR_REQUIRED_MESSAGE);
        }
        String competencia = String.format(Locale.ROOT, "%04d-%02d", ano, mes);

        // GET ?ibge=...&competencia=... — UriComponentsBuilder garante
        // encoding correto dos query params em qualquer caracter especial.
        URI relativeUri = UriComponentsBuilder.fromPath(SCRAPE_PATH)
                .queryParam("ibge", ibge6)
                .queryParam("competencia", competencia)
                .build()
                .toUri();

        SidecarResponse response;
        try {
            response = sidecarClient.get()
                    .uri(relativeUri)
                    .retrieve()
                    .body(SidecarResponse.class);
        } catch (RestClientResponseException ex) {
            String detail = extractDetail(ex);
            if (ex.getStatusCode().value() == 400) {
                // Bad input shouldn't open the CB; rethrow with a message that
                // reflects the upstream rejection rather than a generic fail.
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "SISAB sidecar rejeitou parâmetros: " + detail, ex);
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "SISAB sidecar respondeu " + ex.getStatusCode() + ": " + detail, ex);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "SISAB sidecar inacessível: " + ex.getClass().getSimpleName()
                            + " — verifique o container sisab-sidecar.", ex);
        }

        if (response == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "SISAB sidecar devolveu corpo vazio.");
        }
        if (response.empty() || response.rows() == null || response.rows().isEmpty()) {
            log.info("SISAB sidecar reportou conjunto vazio para IBGE={} comp={}", ibge6, competencia);
            return List.of();
        }

        List<ProducaoSisabResponse> rows = new ArrayList<>(response.rows().size());
        for (Map<String, Object> raw : response.rows()) {
            ProducaoSisabResponse parsed = parseRow(raw, ibge6);
            if (parsed != null) {
                rows.add(parsed);
            }
        }
        log.info("SISAB sidecar resolved {} rows for IBGE={} comp={}",
                rows.size(), ibge6, competencia);
        return rows;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<ProducaoSisabResponse> fallback(String ibge6, int ano, int mes, Throwable cause) {
        log.warn("SISAB fallback triggered for IBGE={} {}/{} cause={}", ibge6, mes, ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "SISAB indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Maps one raw row from the sidecar to {@link ProducaoSisabResponse}.
     *
     * <p>The sidecar streams every column from the SISAB DataTable verbatim
     * (REGIAO, UF, IBGE, MUNICIPIO, CNES, INE, VALIDACAO, TOTAL, …). Our
     * public DTO only exposes the four fields a downstream consumer needs
     * to act on: ibge, cnes, ine, statusValidacao. The remaining columns
     * are tolerated but ignored.</p>
     *
     * <p>Numeric fields ({@code IBGE}, {@code CNES}) sometimes arrive as
     * {@code Number} from Pandas-to-JSON when Pandas inferred them as int;
     * {@link #toCleanString} normalises both shapes to a string and strips
     * the trailing {@code .0} that {@code Double#toString} adds.</p>
     */
    private ProducaoSisabResponse parseRow(Map<String, Object> raw, String fallbackIbge) {
        if (raw == null || raw.isEmpty()) return null;

        String ibge = toCleanString(lookupKey(raw, "IBGE"));
        if (ibge == null || ibge.isBlank()) ibge = fallbackIbge;

        String cnes = toCleanString(lookupKey(raw, "CNES"));
        String ine = toCleanString(lookupKey(raw, "INE"));
        // Pandas preserves Portuguese diacritics in some headers (e.g.
        // "Validação", "Região"); SISAB has reordered/renamed columns in
        // the past. Look up keys via accent-and-case insensitive normalisation
        // so future drift on the upstream side does not silently turn
        // "Aprovado" into "DESCONHECIDO".
        String validacao = toCleanString(lookupKey(raw, "VALIDACAO"));

        if (cnes == null || cnes.isBlank() || ine == null || ine.isBlank()) {
            log.debug("SISAB row skipped (missing CNES or INE): {}", raw);
            return null;
        }
        return new ProducaoSisabResponse(
                ibge,
                cnes,
                ine,
                (validacao == null || validacao.isBlank()) ? "DESCONHECIDO" : validacao
        );
    }

    /**
     * Coerces a JSON-deserialised value to a clean string, stripping the
     * trailing {@code .0} that Pandas-to-JSON adds when a column is inferred
     * as {@code float64}. Returns {@code null} for null or blank input.
     */
    private static String toCleanString(Object value) {
        if (value == null) return null;
        String str;
        if (value instanceof Number num) {
            double d = num.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                str = Long.toString((long) d);
            } else {
                str = num.toString();
            }
        } else {
            str = value.toString();
        }
        String trimmed = str.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Returns the value for the first key of {@code raw} that matches the
     * given canonical key after stripping accents, lowercasing and removing
     * non-alphanumerics. So both {@code "Validação"} and {@code "VALIDACAO"}
     * resolve to the same lookup, surviving casing/accent drift on the
     * Pandas-to-JSON projection in the sidecar.
     */
    private static Object lookupKey(Map<String, Object> raw, String canonicalKey) {
        String target = normalizeKey(canonicalKey);
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (normalizeKey(e.getKey()).equals(target)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String normalizeKey(String key) {
        if (key == null) return "";
        String stripped = Normalizer.normalize(key, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        return stripped.replaceAll("[^a-z0-9]", "");
    }

    /** Best-effort extraction of FastAPI's {@code detail} field from an error body. */
    private static String extractDetail(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) return ex.getStatusText();
        int idx = body.indexOf("\"detail\":");
        if (idx < 0) return body.length() > 200 ? body.substring(0, 200) : body;
        int q1 = body.indexOf('"', idx + 9);
        int q2 = q1 >= 0 ? body.indexOf('"', q1 + 1) : -1;
        if (q1 < 0 || q2 < 0) return body;
        return body.substring(q1 + 1, q2);
    }

    /**
     * Wire-shape of the sidecar response. Matches {@code app.py:ScrapeResponse}.
     * Unknown fields are ignored so additive changes on the sidecar (e.g., new
     * meta fields) do not break the gateway.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SidecarResponse(
            List<Map<String, Object>> rows,
            String competencia,
            boolean empty,
            int count
    ) {
    }
}
