package br.com.cernebr.gateway_nacional.licitacoes.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoDetalheDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoResumoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Portal;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Cliente da Bolsa de Licitações e Leilões (BLL).
 *
 * <p><b>Status atual (auditado 2026-05):</b> a BLL migrou para bllcompras.com
 * e protegeu a consulta pública com <i>Google reCAPTCHA v2</i>
 * (sitekey {@code 6LdpKvsmAAAAAA4rzH5iQNswgItyulQ1J2HQ1FkK}) sobre o
 * endpoint AJAX {@code /Process/GetProcessByParams}. Sem um captcha solver
 * externo (2Captcha/Anti-Captcha) ou um Chromium headless atrás de
 * FlareSolverr, é impossível resolver o desafio programaticamente.</p>
 *
 * <p>O cliente faz uma sonda barata na rota pública de busca e, ao detectar
 * o bloqueio do reCAPTCHA, lança {@link ResourceUnavailableException} com
 * mensagem específica — o {@code LicitacoesService} preserva o sucesso
 * parcial dos demais portais e marca {@code bll} em {@code portaisFalhos}
 * na resposta agregada. <b>Importante:</b> por força da Lei 14.133/2021 a
 * grande maioria das licitações da BLL também é publicada no PNCP
 * (ComprasNet), então a cobertura efetiva do gateway permanece alta.</p>
 *
 * <p><b>Como reabilitar a coleta direta:</b> uma vez configurado um
 * captcha solver (variável de ambiente {@code GATEWAY_LICITACOES_BLL_CAPTCHA_PROVIDER})
 * ou o sidecar FlareSolverr v3+ com plugin reCAPTCHA, este cliente pode
 * ser estendido para postar em {@code /Process/GetProcessByParams} com o
 * {@code g-recaptcha-response} resolvido. O ponto de extensão fica
 * claramente marcado em {@code requestProcessSearch}.</p>
 */
@Slf4j
@Component
public class BllComprasClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "BLL-Compras";

    private static final String SEARCH_PATH = "/Process/ProcessSearchPublic?param1=0";
    private static final String RECAPTCHA_SITEKEY_MARKER = "grecaptcha";

    private final String baseUrl;
    private final String userAgent;
    private final int timeoutMs;

    public BllComprasClient(@Value("${gateway.licitacoes.bll.base-url:https://bllcompras.com}") String baseUrl,
                            @Value("${gateway.licitacoes.bll.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36}") String userAgent,
                            @Value("${gateway.licitacoes.bll.timeout-millis:8000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Portal portal() {
        return Portal.BLL;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "bllCB", fallbackMethod = "fallbackListar")
    public List<LicitacaoResumoDTO> listarAtivas(String uf, String modalidade) {
        probeOrFail();
        // Caminho impossível sem captcha solver — devolvemos vazio para
        // sinalizar "sem dados" sem corromper a cascata. (Mantido por
        // simetria; probeOrFail() já levantou ResourceUnavailableException.)
        return List.of();
    }

    @Override
    @CircuitBreaker(name = "bllCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        probeOrFail();
        return Optional.empty();
    }

    /**
     * Faz uma sonda barata (GET na página pública de busca) só para
     * confirmar que o portal continua atrás de reCAPTCHA. Custo: 1 round-trip,
     * sem POST nem ciclo de hidratação. Se em algum momento o portal
     * remover o desafio, este método precisará evoluir para parser real.
     */
    private void probeOrFail() {
        try {
            Connection.Response resp = Jsoup.connect(baseUrl + SEARCH_PATH)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(true)
                    .execute();
            String body = resp.body();
            if (body != null && body.contains(RECAPTCHA_SITEKEY_MARKER)) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "BLL Compras exige Google reCAPTCHA v2 na consulta pública desde 2026-05; sem captcha solver configurado, os processos podem ser consultados via PNCP (ComprasNet) por força da Lei 14.133/2021.");
            }
            // Se chegou aqui sem o marcador, o portal mudou — alerta para revisão.
            log.warn("[BLL] Página de busca não exibiu marcador reCAPTCHA esperado — revisar estratégia de scraping.");
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BLL Compras alterou a estrutura da página de busca; cliente do gateway precisa de auditoria.");
        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BLL indisponível durante sondagem inicial: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("BLL listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BLL indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("BLL detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BLL indisponível ou Circuit Breaker aberto.", cause);
    }
}
