package br.com.cernebr.gateway_nacional.licitacoes.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.licitacoes.captcha.CaptchaSolverEngine;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoDetalheDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoResumoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Portal;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Cliente da Bolsa Nacional de Compras (BNC — bnccompras.com).
 *
 * <p><b>Plataforma:</b> BNC roda o mesmo motor ASP.NET MVC que a BLL, com
 * endpoints idênticos ({@code /Process/ProcessSearchPublic},
 * {@code /Process/ProcessInformation}, {@code /Process/ProcessFiles}).
 * O único diferencial é o domínio e o sitekey de reCAPTCHA:
 * {@value #BNC_SITEKEY}.</p>
 *
 * <p>Este cliente delega toda a lógica de scraping para
 * {@link BllComprasClient}, reconfigurado com a URL e o sitekey da BNC.
 * Manutenção em um único lugar — mudanças na plataforma se refletem aqui
 * automaticamente.</p>
 */
@Slf4j
@Component
public class BncClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "BNC";
    static final String BNC_SITEKEY = "6LestvomAAAAAG9MNzlBaMEufF1QLdpKoL48qGsq";

    private final BllComprasClient delegate;

    public BncClient(
            @Value("${gateway.licitacoes.bnc.base-url:https://bnccompras.com}") String baseUrl,
            @Value("${gateway.licitacoes.bnc.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36}") String userAgent,
            @Value("${gateway.licitacoes.bnc.timeout-millis:12000}") int timeoutMs,
            CaptchaSolverEngine captchaSolver) {
        this.delegate = new BncDelegate(baseUrl, userAgent, timeoutMs, captchaSolver);
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
        List<LicitacaoResumoDTO> rows = delegate.listarAtivas(uf, modalidade);
        // Re-tagueia os resumos com o portal BNC correto
        return rows.stream()
                .map(r -> new LicitacaoResumoDTO(
                        Portal.BNC, r.identificador(), r.numero(), r.objetoResumido(),
                        r.modalidade(), r.uf(), r.orgao(), r.dataAbertura(),
                        r.dataEncerramento(), r.valorEstimado(), r.urlOriginal()))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "bncCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        return delegate.buscarDetalhe(identificador)
                .map(d -> new LicitacaoDetalheDTO(
                        Portal.BNC, d.identificador(), d.numero(), d.objeto(),
                        d.modalidade(), d.modalidadeOriginal(), d.uf(), d.orgao(),
                        d.dataAbertura(), d.dataEncerramento(), d.dataPublicacao(),
                        d.valorEstimado(), d.urlOriginal(), d.situacao(),
                        d.itens(), d.anexos()));
    }

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("[BNC] listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BNC indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("[BNC] detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BNC indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Subclasse interna que herda a lógica de scraping da BLL mas usa a URL
     * e o sitekey da BNC. O override de {@code portal()} e
     * {@code providerName()} não é chamado externamente (BncClient os cobre).
     */
    private static class BncDelegate extends BllComprasClient {

        BncDelegate(String baseUrl, String userAgent, int timeoutMs,
                    CaptchaSolverEngine captchaSolver) {
            super(baseUrl, userAgent, timeoutMs, captchaSolver);
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
        protected String siteKey() {
            return BNC_SITEKEY;
        }
    }
}
