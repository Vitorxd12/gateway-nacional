package br.com.cernebr.gateway_nacional.licitacoes.captcha;

import java.util.Optional;

/**
 * Abstração de resolução de reCAPTCHA v2.
 *
 * <p>Implementações concretas envolvem serviços de OCR/IA via API REST
 * (CapSolver, 2Captcha). A implementação nula ({@code NullCaptchaEngine})
 * é registrada quando nenhuma chave está configurada e sempre devolve
 * {@link Optional#empty()}, preservando o comportamento de degradação
 * parcial existente nos clientes BLL/BNC.</p>
 */
public interface CaptchaSolverEngine {

    /**
     * Solicita a resolução de um reCAPTCHA v2 (visível ou invisível) para
     * a página informada. A chamada é bloqueante — aguarda o solver até o
     * token ficar disponível ou o timeout do circuit breaker cancelar a
     * virtual thread.
     *
     * @param siteKey sitekey do widget reCAPTCHA da página alvo
     * @param pageUrl URL completa onde o captcha aparece (enviada ao solver
     *                para contexto de renderização)
     * @return token {@code gRecaptchaResponse} pronto para injetar no POST,
     *         ou {@code empty} se o solver não estiver disponível
     */
    Optional<String> solveV2(String siteKey, String pageUrl);

    /**
     * Indica se este engine consegue realmente resolver captchas. Retorna
     * {@code false} na implementação nula (sem chave configurada) para
     * evitar tentativas inúteis e log noise.
     */
    boolean available();
}
