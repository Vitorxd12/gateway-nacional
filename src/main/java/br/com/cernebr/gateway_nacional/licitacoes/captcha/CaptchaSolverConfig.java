package br.com.cernebr.gateway_nacional.licitacoes.captcha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configura o {@link CaptchaSolverEngine} ativo com base nas variáveis de
 * ambiente {@code GATEWAY_CAPTCHA_SOLVER_PROVIDER} e
 * {@code GATEWAY_CAPTCHA_SOLVER_KEY}.
 *
 * <p>Sem chave configurada (default): {@link NullCaptchaEngine} — os clientes
 * BLL/BNC degradam para scraping da página inicial (100 itens, sem captcha).
 *
 * <p>Com chave configurada:
 * <ul>
 *   <li>{@code GATEWAY_CAPTCHA_SOLVER_PROVIDER=capsolver} (default) →
 *       {@link CapsolverCaptchaEngine} (recomendado — mais rápido, ~3-5s).</li>
 *   <li>{@code GATEWAY_CAPTCHA_SOLVER_PROVIDER=2captcha} →
 *       {@link TwoCaptchaEngine}.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class CaptchaSolverConfig {

    @Bean
    public CaptchaSolverEngine captchaSolverEngine(
            @Value("${gateway.captcha.solver.provider:capsolver}") String provider,
            @Value("${gateway.captcha.solver.key:}") String key,
            RestClient.Builder builder) {

        if (key == null || key.isBlank()) {
            log.info("[CaptchaSolver] Nenhuma chave configurada — BLL/BNC usarão scraping sem filtro de UF por servidor.");
            return new NullCaptchaEngine();
        }

        if ("2captcha".equalsIgnoreCase(provider)) {
            log.info("[CaptchaSolver] Provider: 2Captcha");
            return new TwoCaptchaEngine(key,
                    builder.baseUrl("https://2captcha.com")
                           .defaultHeader("Content-Type", "application/json")
                           .build());
        }

        log.info("[CaptchaSolver] Provider: CapSolver");
        return new CapsolverCaptchaEngine(key,
                builder.baseUrl("https://api.capsolver.com")
                       .defaultHeader("Content-Type", "application/json")
                       .build());
    }
}
