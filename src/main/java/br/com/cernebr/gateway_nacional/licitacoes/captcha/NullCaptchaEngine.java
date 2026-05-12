package br.com.cernebr.gateway_nacional.licitacoes.captcha;

import java.util.Optional;

/**
 * Engine nulo — ativado quando {@code GATEWAY_CAPTCHA_SOLVER_KEY} não está
 * definido. Não faz chamadas de rede; apenas sinaliza indisponibilidade para
 * que os clientes BLL/BNC degradem para o scraping da página inicial (100
 * itens pré-renderizados, sem filtro de UF pelo servidor).
 */
public class NullCaptchaEngine implements CaptchaSolverEngine {

    @Override
    public Optional<String> solveV2(String siteKey, String pageUrl) {
        return Optional.empty();
    }

    @Override
    public boolean available() {
        return false;
    }
}
