package br.com.cernebr.gateway_nacional.config;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Thin Java client for the <a href="https://github.com/FlareSolverr/FlareSolverr">FlareSolverr</a>
 * sidecar — a headless-Chromium proxy that bypasses Cloudflare / F5 WAFs and
 * returns the resolved page body. Activated on demand: when the
 * {@code gateway.flaresolverr.url} property is empty (default), the invoker
 * is in "disabled" state and {@link #isEnabled()} returns {@code false}.
 *
 * <h2>Architectural posture</h2>
 * <p>This class is the single source of truth for the FlareSolverr handshake
 * across the gateway, so every client that needs anti-bot bypass goes through
 * the same code path. Two cmds are supported:
 * <ul>
 *   <li>{@code request.get} — fetch a URL via {@link #get(String)} or
 *       {@link #get(String, List)} when continuing a cookie session;</li>
 *   <li>{@code request.post} — submit a URL-encoded form via
 *       {@link #post(String, Map, List)}, used by the SISAB JSF dance.</li>
 * </ul>
 *
 * <p>FlareSolverr returns the resolved body as a string, plus the cookie jar
 * after challenges. The {@link FlareResult} record exposes both, so callers
 * can chain a GET → POST flow (e.g., capture {@code javax.faces.ViewState}
 * on GET, replay the JSESSIONID cookies on the form POST).</p>
 *
 * <p>All upstream failures (timeouts, FlareSolverr {@code status: error}
 * responses, HTTP ≥ 400 from the protected upstream) are converted to
 * {@link ResourceUnavailableException} so the calling client's
 * {@code @CircuitBreaker} counts them uniformly.</p>
 */
@Slf4j
@Component
public class FlareSolverrInvoker {

    private static final String PROVIDER_NAME = "FlareSolverr";
    private static final String SOLVE_PATH = "/v1";

    private final RestClient restClient;
    private final boolean enabled;
    private final int maxTimeoutMs;

    public FlareSolverrInvoker(RestClient.Builder builder,
                               @Value("${gateway.flaresolverr.url:}") String flaresolverrUrl,
                               @Value("${gateway.flaresolverr.max-timeout-ms:60000}") int maxTimeoutMs) {
        this.maxTimeoutMs = maxTimeoutMs;
        this.enabled = flaresolverrUrl != null && !flaresolverrUrl.isBlank();
        // O builder global ({@link RestClientConfig}) carrega readTimeout=5s para
        // proteger chamadas curtas a APIs REST. Chamadas ao FlareSolverr rodam
        // Chromium headless e tipicamente levam 8–30s; usar o timeout global
        // dispararia ResourceAccessException antes do sidecar responder. Por
        // isso o invoker monta seu próprio RestClient com readTimeout alinhado
        // ao {@code maxTimeoutMs} configurado (+ 5s de folga para o handshake).
        this.restClient = enabled
                ? builder.requestFactory(buildLongReadFactory(maxTimeoutMs))
                        .baseUrl(flaresolverrUrl)
                        .build()
                : null;
        if (enabled) {
            log.info("FlareSolverr sidecar configured at {} (read-timeout={}ms)",
                    flaresolverrUrl, maxTimeoutMs + 5000);
        }
    }

    /**
     * Cria um {@link JdkClientHttpRequestFactory} dedicado com leitura tolerante
     * à latência do FlareSolverr. Usa um executor de virtual threads próprio
     * para não competir pelo executor global do {@link HttpClient} padrão.
     */
    private static JdkClientHttpRequestFactory buildLongReadFactory(int maxTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis((long) maxTimeoutMs + 5_000L));
        return factory;
    }

    /**
     * @return {@code true} when {@code gateway.flaresolverr.url} is non-blank.
     *         Callers MUST guard their code paths on this — invoking
     *         {@link #get(String)} et al. on a disabled invoker throws an
     *         {@link IllegalStateException}.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public FlareResult get(String targetUrl) {
        return get(targetUrl, List.of());
    }

    public FlareResult get(String targetUrl, List<FlareCookie> incomingCookies) {
        Map<String, Object> payload = baseCommand("request.get", targetUrl, incomingCookies);
        return invoke(payload);
    }

    public FlareResult post(String targetUrl, Map<String, String> formData, List<FlareCookie> incomingCookies) {
        Map<String, Object> payload = baseCommand("request.post", targetUrl, incomingCookies);
        payload.put("postData", urlEncode(formData));
        return invoke(payload);
    }

    /**
     * Creates a persistent FlareSolverr browser session — same Chromium
     * context (cookies, viewport, fingerprint) reused across calls. Required
     * for stateful upstream protocols like JSF/PrimeFaces, where the
     * {@code javax.faces.ViewState} captured on a GET binds to the JSESSIONID
     * issued in the same TCP/JS context. Single-call FlareSolverr
     * ({@link #get(String)}, {@link #post(String, Map, List)}) starts a
     * fresh Chromium per hit and trips
     * {@code javax.faces.application.ViewExpiredException} on the form POST.
     *
     * @return the FlareSolverr session id; pass it to
     *         {@link #getInSession(String, String)} /
     *         {@link #postInSession(String, Map, String)} and call
     *         {@link #destroySession(String)} when done.
     */
    public String createSession() {
        if (!enabled) {
            throw new IllegalStateException("FlareSolverrInvoker called while disabled — guard with isEnabled() first.");
        }
        SessionEnvelope envelope;
        try {
            envelope = restClient.post()
                    .uri(SOLVE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cmd", "sessions.create"))
                    .retrieve()
                    .body(SessionEnvelope.class);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FlareSolverr falhou ao criar session: " + ex.getClass().getSimpleName(), ex);
        }
        if (envelope == null || envelope.session() == null || envelope.session().isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FlareSolverr não devolveu session id na criação.");
        }
        return envelope.session();
    }

    /**
     * Tears down a previously-created FlareSolverr session — releases the
     * Chromium context. Best-effort; failure is swallowed because there is
     * nothing the caller can do, and the FlareSolverr container reaps idle
     * sessions on its own ({@code BROWSER_TIMEOUT}).
     */
    public void destroySession(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) return;
        try {
            restClient.post()
                    .uri(SOLVE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cmd", "sessions.destroy", "session", sessionId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.debug("FlareSolverr destroySession({}) ignored: {}", sessionId, ex.getMessage());
        }
    }

    public FlareResult getInSession(String targetUrl, String sessionId) {
        Map<String, Object> payload = baseCommand("request.get", targetUrl, List.of());
        if (sessionId != null && !sessionId.isBlank()) payload.put("session", sessionId);
        return invoke(payload);
    }

    public FlareResult postInSession(String targetUrl, Map<String, String> formData, String sessionId) {
        Map<String, Object> payload = baseCommand("request.post", targetUrl, List.of());
        payload.put("postData", urlEncode(formData));
        if (sessionId != null && !sessionId.isBlank()) payload.put("session", sessionId);
        return invoke(payload);
    }

    private Map<String, Object> baseCommand(String cmd, String targetUrl, List<FlareCookie> incomingCookies) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmd", cmd);
        payload.put("url", targetUrl);
        payload.put("maxTimeout", maxTimeoutMs);
        List<FlareCookie> sanitized = sanitizeCookies(incomingCookies);
        if (!sanitized.isEmpty()) {
            payload.put("cookies", sanitized);
        }
        return payload;
    }

    /**
     * Filters out cookies whose name contains characters that the underlying
     * Chromium driver cannot route through its WebDriver protocol. F5 BIG-IP
     * occasionally issues session-affinity cookies with literal {@code /}
     * in the name (e.g., {@code BIGipServerEI216T7OCd1WTwga/7fQVQ}) which
     * the WebDriver mistakes for a URL path segment, returning HTTP 500
     * with {@code unknown command: session/.../cookie/...}. These cookies
     * are routinely regenerated by the upstream on the next request, so
     * dropping them is safe — and considerably better than the alternative
     * of the whole call failing.
     */
    private static List<FlareCookie> sanitizeCookies(List<FlareCookie> cookies) {
        if (cookies == null || cookies.isEmpty()) return List.of();
        List<FlareCookie> result = new java.util.ArrayList<>(cookies.size());
        for (FlareCookie c : cookies) {
            if (c == null || c.name() == null || c.name().isBlank()) continue;
            // Allow only RFC 6265 token chars in the cookie name. Anything
            // outside that set is the F5 path-affinity garbage above.
            if (c.name().chars().allMatch(ch ->
                    (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.')) {
                result.add(c);
            }
        }
        return result;
    }

    private FlareResult invoke(Map<String, Object> payload) {
        if (!enabled) {
            throw new IllegalStateException("FlareSolverrInvoker called while disabled — guard with isEnabled() first.");
        }

        FlareResponse response;
        try {
            response = restClient.post()
                    .uri(SOLVE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(FlareResponse.class);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FlareSolverr inacessível: " + ex.getClass().getSimpleName(), ex);
        }

        if (response == null || !"ok".equalsIgnoreCase(response.status())) {
            String detail = response == null ? "resposta vazia" : ("status=" + response.status() + " msg=" + response.message());
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FlareSolverr não resolveu o desafio: " + detail);
        }
        FlareSolution solution = response.solution();
        if (solution == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FlareSolverr devolveu resposta sem solution.");
        }
        if (solution.status() >= 400) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Upstream protegido devolveu HTTP " + solution.status() + " mesmo via FlareSolverr (" + solution.url() + ").");
        }
        return new FlareResult(
                solution.response() != null ? solution.response() : "",
                solution.cookies() != null ? solution.cookies() : List.of(),
                solution.status()
        );
    }

    private static String urlEncode(Map<String, String> form) {
        if (form == null || form.isEmpty()) return "";
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue() != null ? e.getValue() : "", StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /**
     * Resolved page body plus the post-challenge cookie jar.
     *
     * @param body    the upstream response body — JSON or HTML, depending
     *                on what the protected endpoint serves;
     * @param cookies cookies set by the upstream after FlareSolverr cleared
     *                the WAF challenge — replay these on follow-up calls
     *                that need session continuity (SISAB GET → POST);
     * @param status  the HTTP status the protected upstream returned to
     *                FlareSolverr (typically 200; ≥ 400 was rejected before
     *                we got here).
     */
    public record FlareResult(String body, List<FlareCookie> cookies, int status) {
        /** Convenience accessor — the FlareSolverr API field is {@code response}. */
        public String html() { return body; }

        /**
         * Returns the body unwrapped from Chrome's "raw JSON viewer" template.
         *
         * <p>FlareSolverr drives a real Chromium. When a gov.br endpoint
         * answers JSON without {@code Content-Type: application/json},
         * Chrome wraps the payload as {@code <html><body><pre>{...}</pre></body></html>}
         * for human-readable rendering. The wrapper trips
         * {@code ObjectMapper.readTree()} on the very first character.</p>
         *
         * <p>This helper detects the wrapper (body does not start with
         * {@code {} or {@code [}) and uses Jsoup to extract the {@code <pre>}
         * content. Bodies that are already raw JSON pass through unchanged;
         * if no {@code <pre>} is found, the original body is returned and
         * the caller's parser surfaces the failure with the real upstream
         * payload.</p>
         */
        public String jsonBody() {
            if (body == null || body.isEmpty()) return body;
            String trimmed = body.stripLeading();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return body;
            }
            try {
                Document doc = Jsoup.parse(body);
                Element pre = doc.selectFirst("pre");
                if (pre != null) {
                    String extracted = pre.text();
                    if (!extracted.isBlank()) {
                        return extracted;
                    }
                }
            } catch (Exception ignored) {
                // fall through — return the body as-is so the caller's
                // JSON parser produces a meaningful error message.
            }
            return body;
        }
    }

    /**
     * FlareSolverr cookie shape — same JSON wire format on input and output,
     * so a {@link #get(String)} result can feed straight into a
     * {@link #post(String, Map, List)} without conversion.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlareCookie(String name, String value, String domain, String path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FlareResponse(String status, String message, FlareSolution solution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FlareSolution(String url, int status, String response, List<FlareCookie> cookies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SessionEnvelope(String status, String message, String session) {
    }
}
