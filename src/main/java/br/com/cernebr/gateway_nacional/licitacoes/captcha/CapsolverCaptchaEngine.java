package br.com.cernebr.gateway_nacional.licitacoes.captcha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Engine de resolução de reCAPTCHA v2 via <a href="https://capsolver.com">CapSolver</a>.
 *
 * <p>Fluxo:
 * <ol>
 *   <li>POST {@code /createTask} com tipo {@code ReCaptchaV2TaskProxyless}.</li>
 *   <li>Poll {@code /getTaskResult} com intervalo {@value #POLL_INTERVAL_MS} ms
 *       até status {@code ready} ou esgotamento de {@value #MAX_POLLS} tentativas.</li>
 * </ol>
 *
 * <p>A chave de API é injetada via {@code GATEWAY_CAPTCHA_SOLVER_KEY}. Custo
 * médio por resolução: ~R$0,005 (USD 0,001) com créditos CapSolver.</p>
 */
@Slf4j
public class CapsolverCaptchaEngine implements CaptchaSolverEngine {

    private static final int MAX_POLLS = 15;
    private static final long POLL_INTERVAL_MS = 4_000;

    private final String apiKey;
    private final RestClient restClient;

    public CapsolverCaptchaEngine(String apiKey, RestClient restClient) {
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
        // 1. Create task
        Map<String, Object> createBody = Map.of(
                "clientKey", apiKey,
                "task", Map.of(
                        "type", "ReCaptchaV2TaskProxyless",
                        "websiteURL", pageUrl,
                        "websiteKey", siteKey,
                        "isInvisible", true
                )
        );
        Map<String, Object> createResp;
        try {
            createResp = restClient.post()
                    .uri("/createTask")
                    .body(createBody)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception ex) {
            log.warn("[CapSolver] createTask falhou: {}", ex.toString());
            return Optional.empty();
        }

        if (createResp == null || !Integer.valueOf(0).equals(createResp.get("errorId"))) {
            log.warn("[CapSolver] createTask erro: {}", createResp);
            return Optional.empty();
        }
        String taskId = String.valueOf(createResp.get("taskId"));

        // 2. Poll for result
        Map<String, Object> pollBody = Map.of("clientKey", apiKey, "taskId", taskId);
        for (int i = 0; i < MAX_POLLS; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            Map<String, Object> pollResp;
            try {
                pollResp = restClient.post()
                        .uri("/getTaskResult")
                        .body(pollBody)
                        .retrieve()
                        .body(Map.class);
            } catch (Exception ex) {
                log.warn("[CapSolver] getTaskResult falhou: {}", ex.toString());
                return Optional.empty();
            }
            if (pollResp == null) continue;
            if (!"ready".equals(pollResp.get("status"))) continue;

            Map<String, Object> solution = (Map<String, Object>) pollResp.get("solution");
            if (solution != null) {
                String token = (String) solution.get("gRecaptchaResponse");
                if (token != null && !token.isBlank()) {
                    log.debug("[CapSolver] token resolvido para siteKey={}", siteKey);
                    return Optional.of(token);
                }
            }
        }
        log.warn("[CapSolver] timeout após {} polls para siteKey={}", MAX_POLLS, siteKey);
        return Optional.empty();
    }
}
