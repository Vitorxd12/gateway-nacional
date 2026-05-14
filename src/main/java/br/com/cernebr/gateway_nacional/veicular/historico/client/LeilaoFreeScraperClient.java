package br.com.cernebr.gateway_nacional.veicular.historico.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Free-tier leilão-history scraper targeting {@code leilaofree.com.br}.
 *
 * <p>Strategy: GET {@code /placa/{placa}} — the upstream renders the page
 * label-by-label, so the scraper normalises the body text and applies the
 * regex anchors in {@link HistoricoScraperSupport}. Two outcomes:
 * <ul>
 *   <li>page renders, no marker fires → emits a clean evidence
 *       ({@link HistoricoEvidencia#clean(String)}). The source is counted
 *       toward {@code fontesConsultadas} as "answered cleanly";</li>
 *   <li>page renders, markers fire → emits an evidence with
 *       {@code indicioLeilao=true} (and {@code indicioSinistro=true} when
 *       the sinistro anchor also fires inside the same page) and a 240-char
 *       excerpt around the first marker for the audit trail.</li>
 * </ul>
 *
 * <h2>Anti-bot posture</h2>
 * The upstream sits behind Cloudflare. When {@code gateway.flaresolverr.url}
 * is configured, the client routes the GET through the FlareSolverr sidecar
 * (already used by the placa cascade — same handshake). Without the sidecar,
 * the client falls back to a direct Jsoup call with rotated UA — sometimes
 * gets through, often gets 403/523. Either failure path lands as
 * {@link ResourceUnavailableException} so the orchestrator drops this
 * fonte from {@code fontesConsultadas} and continues with the survivors.
 */
@Slf4j
@Component
public class LeilaoFreeScraperClient implements HistoricoScraperClient {

    public static final String PROVIDER_NAME = "LeilaoFree";

    private final String baseUrl;
    private final int timeoutMillis;
    private final FlareSolverrInvoker flareSolverr;

    public LeilaoFreeScraperClient(
            @Value("${gateway.historico.leilaofree.base-url:https://leilaofree.com.br}") String baseUrl,
            @Value("${gateway.historico.leilaofree.timeout-millis:8000}") int timeoutMillis,
            FlareSolverrInvoker flareSolverr) {
        this.baseUrl = baseUrl;
        this.timeoutMillis = timeoutMillis;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "leilaoFreeScraperCB", fallbackMethod = "fallback")
    public HistoricoEvidencia consultar(String placa) {
        String url = baseUrl + "/placa/" + placa;
        Document doc = flareSolverr.isEnabled() ? fetchViaFlare(url) : fetchDirect(url);

        String normalized = HistoricoScraperSupport.normalize(doc.body() != null ? doc.body().text() : "");
        if (normalized.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "LeilaoFree devolveu HTML sem body — provável bloqueio anti-bot.");
        }

        boolean indicioLeilao = HistoricoScraperSupport.matchesLeilao(normalized);
        boolean indicioSinistro = HistoricoScraperSupport.matchesSinistro(normalized);
        String detalhe = (indicioLeilao || indicioSinistro)
                ? HistoricoScraperSupport.excerptAroundMarker(normalized, 80)
                : null;

        if (indicioLeilao || indicioSinistro) {
            log.info("LeilaoFree scraped placa={} indicioLeilao={} indicioSinistro={}",
                    placa, indicioLeilao, indicioSinistro);
        } else {
            log.info("LeilaoFree clean for placa={} (page renderizou, sem marcadores).", placa);
        }
        return new HistoricoEvidencia(PROVIDER_NAME, indicioLeilao, indicioSinistro, detalhe);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private HistoricoEvidencia fallback(String placa, Throwable cause) {
        log.warn("LeilaoFree fallback triggered for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "LeilaoFree indisponível ou Circuit Breaker aberto.", cause);
    }

    private Document fetchDirect(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(HistoricoScraperSupport.pickUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .timeout(timeoutMillis)
                    .get();
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "LeilaoFree inacessível (" + ex.getClass().getSimpleName()
                            + "). Ative o sidecar FlareSolverr para contornar Cloudflare.", ex);
        }
    }

    private Document fetchViaFlare(String url) {
        FlareSolverrInvoker.FlareResult result = flareSolverr.get(url);
        return Jsoup.parse(result.html(), url);
    }
}
