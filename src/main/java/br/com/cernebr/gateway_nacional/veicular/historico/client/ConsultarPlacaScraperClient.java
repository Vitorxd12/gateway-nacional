package br.com.cernebr.gateway_nacional.veicular.historico.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Free-tier sinistro-history scraper targeting {@code consultar-placa.com}
 * (with {@code portalods.com.br} configurable as an alternative base URL
 * via {@code gateway.historico.consultarplaca.base-url}).
 *
 * <p>The page renders vehicle metadata in a label-value table; the scraper
 * scans every {@code <td>} pair plus the body text for sinistro/leilão
 * markers via {@link HistoricoScraperSupport}. Both anchors are checked on
 * the same page — many free aggregators surface both kinds of records on
 * a single response, so a single-pass scan is faster and more accurate
 * than two narrower selectors.</p>
 *
 * <p>Resilience posture is the same as {@link LeilaoFreeScraperClient}:
 * FlareSolverr first when configured (the upstream is Cloudflare-fronted
 * and routinely answers 523 on direct hits), Jsoup direct as last resort,
 * RUE on any failure so the orchestrator can drop this fonte and proceed
 * with the survivors.</p>
 */
@Slf4j
@Component
public class ConsultarPlacaScraperClient implements HistoricoScraperClient {

    public static final String PROVIDER_NAME = "ConsultarPlaca";

    private final String baseUrl;
    private final String placaPath;
    private final int timeoutMillis;
    private final FlareSolverrInvoker flareSolverr;

    public ConsultarPlacaScraperClient(
            @Value("${gateway.historico.consultarplaca.base-url:https://consultar-placa.com}") String baseUrl,
            @Value("${gateway.historico.consultarplaca.placa-path:/placa/{placa}}") String placaPath,
            @Value("${gateway.historico.consultarplaca.timeout-millis:8000}") int timeoutMillis,
            FlareSolverrInvoker flareSolverr) {
        this.baseUrl = baseUrl;
        this.placaPath = placaPath;
        this.timeoutMillis = timeoutMillis;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "consultarPlacaScraperCB", fallbackMethod = "fallback")
    public HistoricoEvidencia consultar(String placa) {
        String url = baseUrl + placaPath.replace("{placa}", placa);
        Document doc = flareSolverr.isEnabled() ? fetchViaFlare(url) : fetchDirect(url);

        String bodyText = doc.body() != null ? doc.body().text() : "";
        String normalized = HistoricoScraperSupport.normalize(bodyText);
        if (normalized.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ConsultarPlaca devolveu HTML vazio ou bloqueado.");
        }

        // Aggregator pages frequently rendered marker labels inside <td>
        // pairs ("Indício de sinistro: SIM"). Inspect those first because
        // they carry the authoritative yes/no signal even when free-text
        // elsewhere is ambiguous (e.g., banner with the word leilão).
        boolean tableLeilao = false;
        boolean tableSinistro = false;
        Elements rows = doc.select("tr");
        for (Element row : rows) {
            Elements tds = row.select("> td");
            if (tds.size() >= 2) {
                String label = HistoricoScraperSupport.normalize(tds.get(0).text());
                String value = HistoricoScraperSupport.normalize(tds.get(1).text());
                if (label.contains("leilao") && value.contains("sim")) tableLeilao = true;
                if (label.contains("sinistro") && value.contains("sim")) tableSinistro = true;
            }
        }

        boolean indicioLeilao = tableLeilao || HistoricoScraperSupport.matchesLeilao(normalized);
        boolean indicioSinistro = tableSinistro || HistoricoScraperSupport.matchesSinistro(normalized);
        String detalhe = (indicioLeilao || indicioSinistro)
                ? HistoricoScraperSupport.excerptAroundMarker(normalized, 100)
                : null;

        log.info("ConsultarPlaca scraped placa={} indicioLeilao={} indicioSinistro={}",
                placa, indicioLeilao, indicioSinistro);
        return new HistoricoEvidencia(PROVIDER_NAME, indicioLeilao, indicioSinistro, detalhe);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private HistoricoEvidencia fallback(String placa, Throwable cause) {
        log.warn("ConsultarPlaca fallback triggered for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ConsultarPlaca indisponível ou Circuit Breaker aberto.", cause);
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
                    "ConsultarPlaca inacessível (" + ex.getClass().getSimpleName()
                            + "). Ative o sidecar FlareSolverr para contornar Cloudflare.", ex);
        }
    }

    private Document fetchViaFlare(String url) {
        FlareSolverrInvoker.FlareResult result = flareSolverr.get(url);
        return Jsoup.parse(result.html(), url);
    }
}
