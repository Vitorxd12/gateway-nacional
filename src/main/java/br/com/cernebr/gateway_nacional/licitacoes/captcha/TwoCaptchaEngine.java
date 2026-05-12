package br.com.cernebr.gateway_nacional.licitacoes.captcha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Engine de resolução de reCAPTCHA v2 via <a href="https://2captcha.com">2Captcha</a>.
 *
 * <p>Protocolo REST v1:
 * <ol>
 *   <li>POST {@code /in.php} (JSON) — submete tarefa e recebe {@code request} (taskId).</li>
 *   <li>Poll {@code /res.php} com {@code action=get} até status {@code 1} ou timeout.</li>
 * </ol>
 *
 * <p>Custo médio: ~USD 0,002 por resolução de reCAPTCHA v2 invisível.</p>
 */
@Slf4j
public class TwoCaptchaEngine implements CaptchaSolverEngine {

    private static final int MAX_POLLS = 20;
    private static final long POLL_INTERVAL_MS = 5_000;
    private static final long INITIAL_WAIT_MS = 10_000;

    private final String apiKey;
    private final RestClient restClient;

    public TwoCaptchaEngine(String apiKey, RestClient restClient) {
        this.apiKey = apiKey;
        this.restClient = restClient;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> solveV2(String siteKey, String pageUrl) {
        // 1. Submit task
        Map<String, Object> submitBody = Map.of(
                "key", apiKey,
                "method", "userrecaptcha",
                "googlekey", siteKey,
                "pageurl", pageUrl,
                "invisible", 1,
                "json", 1
        );
        Map<String, Object> submitResp;
        try {
            submitResp = restClient.post()
                    .uri("/in.php")
                    .body(submitBody)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception ex) {
            log.warn("[2Captcha] submit falhou: {}", ex.toString());
            return Optional.empty();
        }

        if (submitResp == null || !Integer.valueOf(1).equals(submitResp.get("status"))) {
            log.warn("[2Captcha] submit erro: {}", submitResp);
            return Optional.empty();
        }
        String taskId = String.valueOf(submitResp.get("request"));

        // 2. Initial wait (solvers rarely deliver faster)
        try {
            Thread.sleep(INITIAL_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        // 3. Poll for result
        for (int i = 0; i < MAX_POLLS; i++) {
            Map<String, Object> pollResp;
            try {
                pollResp = restClient.get()
                        .uri(u -> u.path("/res.php")
                                .queryParam("key", apiKey)
                                .queryParam("action", "get")
                                .queryParam("id", taskId)
                                .queryParam("json", "1")
                                .build())
                        .retrieve()
                        .body(Map.class);
            } catch (Exception ex) {
                log.warn("[2Captcha] poll falhou: {}", ex.toString());
                return Optional.empty();
            }

            if (pollResp == null) {
                sleep();
                continue;
            }

            Object status = pollResp.get("status");
            if (Integer.valueOf(1).equals(status)) {
                String token = (String) pollResp.get("request");
                if (token != null && !token.isBlank()) {
                    log.debug("[2Captcha] token resolvido para siteKey={}", siteKey);
                    return Optional.of(token);
                }
            }
            // status=0 com request="CAPCHA_NOT_READY" → continuar polling
            sleep();
        }
        log.warn("[2Captcha] timeout após {} polls para siteKey={}", MAX_POLLS, siteKey);
        return Optional.empty();
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
