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
 * Cliente da Bolsa Nacional de Compras (BNC).
 *
 * <p><b>Status atual (auditado 2026-05):</b> bnccompras.com roda o mesmo
 * motor compartilhado da BLL — toda consulta pública passa por Google
 * reCAPTCHA v2 sobre o endpoint AJAX {@code /Process/GetProcessByParams}.
 * O mecanismo é idêntico ao do {@link BllComprasClient}: sem captcha
 * solver externo a coleta direta é inviável.</p>
 *
 * <p>O cliente faz a mesma sonda barata da BLL e devolve
 * {@link ResourceUnavailableException} com diagnóstico explícito quando o
 * marcador reCAPTCHA é detectado — o {@code LicitacoesService} preserva o
 * sucesso parcial dos demais portais e marca {@code bnc} em
 * {@code portaisFalhos}.</p>
 *
 * <p><b>Cobertura alternativa:</b> licitações públicas que tramitam via
 * BNC também são publicadas no PNCP (Lei 14.133/2021) — o
 * {@link ComprasNetClient} continua entregando o grosso da informação.</p>
 */
@Slf4j
@Component
public class BncClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "BNC";

    private static final String SEARCH_PATH = "/Process/ProcessSearchPublic?param1=0";
    private static final String RECAPTCHA_SITEKEY_MARKER = "grecaptcha";

    private final String baseUrl;
    private final String userAgent;
    private final int timeoutMs;

    public BncClient(@Value("${gateway.licitacoes.bnc.base-url:https://bnccompras.com}") String baseUrl,
                     @Value("${gateway.licitacoes.bnc.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36}") String userAgent,
                     @Value("${gateway.licitacoes.bnc.timeout-millis:8000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Portal portal() {
        return Portal.BNC;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "bncCB", fallbackMethod = "fallbackListar")
    public List<LicitacaoResumoDTO> listarAtivas(String uf, String modalidade) {
        probeOrFail();
        return List.of();
    }

    @Override
    @CircuitBreaker(name = "bncCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        probeOrFail();
        return Optional.empty();
    }

    /**
     * Sonda a página pública (GET) para confirmar o bloqueio por reCAPTCHA.
     * Mantém a forma idêntica à da BLL — o motor é o mesmo, alterações
     * acompanharão a BLL em lockstep.
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
                        "BNC Compras exige Google reCAPTCHA v2 na consulta pública desde 2026-05; sem captcha solver configurado, os processos podem ser consultados via PNCP (ComprasNet) por força da Lei 14.133/2021.");
            }
            log.warn("[BNC] Página de busca não exibiu marcador reCAPTCHA esperado — revisar estratégia de scraping.");
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BNC Compras alterou a estrutura da página de busca; cliente do gateway precisa de auditoria.");
        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BNC indisponível durante sondagem inicial: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("BNC listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BNC indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("BNC detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BNC indisponível ou Circuit Breaker aberto.", cause);
    }
}
