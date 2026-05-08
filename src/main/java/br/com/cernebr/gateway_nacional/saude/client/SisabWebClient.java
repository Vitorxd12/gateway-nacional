package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.dto.ProducaoSisabResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
 * dedicated Python sidecar that owns the browser and exposes a REST
 * endpoint {@code POST /scrape}. The Python implementation reuses the
 * proven-in-production logic from the upstream {@code AutoAPSFinancias}
 * project. From the Java gateway's perspective, the sidecar is just
 * another upstream provider with its own circuit breaker.</p>
 *
 * <h2>Fail-fast when sidecar is not configured</h2>
 * <p>When {@code gateway.saude.sisab.sidecar-url} is empty (default), the
 * call short-circuits with the canonical
 * {@code "Esta rota exige a ativação do sidecar SISAB..."} message —
 * fast 503 instead of futile direct attempts. To enable, deploy the
 * {@code sisab-sidecar} container and inject the URL via env.</p>
 *
 * <h2>Latency and timeout</h2>
 * <p>Cold scrapes routinely take 30–90 s under headless Chromium. The
 * {@code @CircuitBreaker} time-limiter for {@code sisabScraperCB} should
 * therefore be set to at least 120 s — see {@code application.yml}.</p>
 */
@Slf4j
@Component
public class SisabWebClient implements SisabClientProvider {

    public static final String PROVIDER_NAME = "SISAB";
    static final String SIDECAR_REQUIRED_MESSAGE =
            "Esta rota exige a ativação do sidecar SISAB (Selenium + headless Chromium). "
                    + "Sem o sidecar configurado via gateway.saude.sisab.sidecar-url, a rota responde 503 "
                    + "porque a página JSF/Mojarra do SISAB depende de plugins JS Bootstrap Multiselect "
                    + "que não podem ser replicados por HTTP puro.";

    private static final String SCRAPE_PATH = "/scrape";

    private final RestClient sidecarClient;
    private final boolean enabled;

    public SisabWebClient(RestClient.Builder builder,
                          @Value("${gateway.saude.sisab.sidecar-url:}") String sidecarUrl) {
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
     * <p>Why this matters: the JDK's default {@code java.net.http.HttpClient}
     * (which Spring RestClient uses under the hood in Spring Boot 4)
     * negotiates HTTP/2 by default and emits an {@code Upgrade: h2c} +
     * {@code HTTP2-Settings: ...} header on cleartext connections. Uvicorn
     * does not speak HTTP/2 — it returns plain HTTP/1.1, but the upgrade
     * dance corrupts the request stream and the server reads {@code body=b''}
     * even though {@code Content-Length: 51} was on the wire. Validated
     * empirically on 2026-05-08 — see the SISAB integration session.</p>
     *
     * <p>Forcing HTTP/1.1 at the JDK client side eliminates the upgrade
     * preamble entirely. We use a dedicated {@link JdkClientHttpRequestFactory}
     * for this client only — the FlareSolverr / FIPE clients keep the default
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
        String uf = IbgeUfLookup.ufFromIbge(ibge6);
        if (uf == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "SISAB: IBGE inválido para derivação de UF: " + ibge6);
        }
        // The sidecar accepts both yyyy-MM and MM/AAAA — we send yyyy-MM as
        // it matches the gateway's public API contract on the controller side.
        String competencia = String.format(Locale.ROOT, "%04d-%02d", ano, mes);

        Map<String, String> body = Map.of(
                "uf", uf,
                "ibge", ibge6,
                "competencia", competencia
        );

        SidecarResponse response;
        try {
            response = sidecarClient.post()
                    .uri(SCRAPE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(SidecarResponse.class);
        } catch (RestClientResponseException ex) {
            // Sidecar talked back, but with non-2xx — translate the FastAPI
            // detail when present so the user-facing message is useful.
            String detail = extractDetail(ex);
            if (ex.getStatusCode().value() == 400) {
                // Bad input shouldn't open the CB; rethrow as a
                // ResourceUnavailableException with a 4xx-flavoured message.
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
        if (response.empty()) {
            log.info("SISAB sidecar reportou 'Nenhum registro' para IBGE={} comp={}", ibge6, competencia);
            return List.of();
        }
        if (response.rows() == null || response.rows().isEmpty()) {
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
     * are tolerated but ignored — see {@code @JsonIgnoreProperties(ignoreUnknown=true)}
     * on {@link SidecarResponse}.</p>
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
        // The Pandas-derived response preserves Portuguese diacritics in
        // some headers (e.g. "Validação", "Região"), and SISAB has reordered
        // / renamed columns historically. Look up keys via accent-and-case
        // insensitive normalisation so future drift on the upstream side
        // does not silently turn "Aprovado" into "DESCONHECIDO".
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
     * Returns the value for the first key of {@code raw} that matches the
     * given canonical key after stripping accents, lowercasing and removing
     * non-alphanumerics. So both {@code "Validação"} and {@code "VALIDACAO"}
     * resolve to the same lookup, surviving casing/accent drift in the
     * Pandas-to-JSON projection on the sidecar side.
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
     * meta fields) do not break the gateway. Records are nullable-friendly,
     * so {@code competencia=null} and {@code rows=null} pass through cleanly
     * — the calling site already null-guards both.
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
