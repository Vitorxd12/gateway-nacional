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

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Baseline historico fonte backed by placafipe.com — fetched independently
 * of the existing {@code PlacaFipeScraperClient} cascade so the historico
 * pipeline never gets blocked by an upstream UA policy that the placa
 * domain may have to keep tight.
 *
 * <p>The page does not publish leilão or sinistro markers (placafipe.com
 * is a placa→veículo registry), so this source always emits a clean
 * {@link HistoricoEvidencia} when the upstream answers — but it does so
 * with <b>real DOM-extracted data</b> (marca / modelo / ano / cidade
 * pulled out of the rendered HTML table). That is the audit guarantee
 * the orchestrator needs to demonstrate that at least one fonte produced
 * parsed evidence on the live path, even when the two primary scrapers
 * (LeilaoFree / ConsultarPlaca) are down behind Cloudflare/523.
 *
 * <h2>Fetch posture</h2>
 * Rotated browser-grade UA from {@link HistoricoScraperSupport#pickUserAgent()}
 * — placafipe.com lets pass real Chrome fingerprints and rejects the
 * "Mozilla/5.0 (compatible; ...)" string. FlareSolverr is used when the
 * sidecar is configured. Failures convert to RUE so the orchestrator
 * drops this fonte and stays fail-soft.
 */
@Slf4j
@Component
public class PlacaFipeBaselineFonte implements HistoricoScraperClient {

    public static final String PROVIDER_NAME = "PlacaFipe";

    private final String baseUrl;
    private final int timeoutMillis;
    private final FlareSolverrInvoker flareSolverr;

    public PlacaFipeBaselineFonte(
            @Value("${gateway.placa.placafipe.base-url:https://placafipe.com}") String baseUrl,
            @Value("${gateway.historico.placafipe.timeout-millis:6000}") int timeoutMillis,
            FlareSolverrInvoker flareSolverr) {
        this.baseUrl = baseUrl;
        this.timeoutMillis = timeoutMillis;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "placaFipeBaselineCB", fallbackMethod = "fallback")
    public HistoricoEvidencia consultar(String placa) {
        String url = baseUrl + "/placa/" + placa;
        Document doc = flareSolverr.isEnabled() ? fetchViaFlare(url) : fetchDirect(url);

        Map<String, String> labels = scanLabelValuePairs(doc);
        String marca = labels.get("marca");
        String modelo = labels.get("modelo");
        if (marca == null && modelo == null) {
            // page rendered but layout drifted — surface as failure so the
            // orchestrator records a real outcome instead of a clean DTO
            // with empty detalhe (which would silently mask the drift).
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "PlacaFipe respondeu mas nenhuma label de veículo foi extraída — placa inexistente ou layout alterado.");
        }

        String detalhe = formatBaseline(
                marca,
                modelo,
                labels.get("ano"),
                labels.get("municipio"),
                labels.get("uf")
        );
        log.info("PlacaFipe baseline historico placa={} detalhe={}", placa, detalhe);
        return new HistoricoEvidencia(PROVIDER_NAME, false, false, detalhe);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private HistoricoEvidencia fallback(String placa, Throwable cause) {
        log.warn("PlacaFipe baseline fallback for placa={} cause={}", placa, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "PlacaFipe baseline indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Minimal browser-grade fetch. Empirically, adding the full sec-ch-ua /
     * sec-fetch-* header set trips Cloudflare on this domain — fewer
     * headers + a real Chrome UA get the canonical page back. Brotli is
     * explicitly NOT requested because Java's HttpUrlConnection (which
     * Jsoup uses under the hood) does not decode {@code br} and would
     * yield garbage bytes when Cloudflare serves brotli-encoded HTML.
     * {@code ignoreHttpErrors(true)} keeps the body available on 403 so
     * the parse layer can decide if Cloudflare interstitial vs real
     * content was served.
     */
    private Document fetchDirect(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(HistoricoScraperSupport.pickUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .referrer(baseUrl + "/")
                    .ignoreHttpErrors(true)
                    .maxBodySize(0)
                    .timeout(timeoutMillis)
                    .get();
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "PlacaFipe baseline inacessível (" + ex.getClass().getSimpleName()
                            + "). Ative o sidecar FlareSolverr para contornar Cloudflare.", ex);
        }
    }

    private Document fetchViaFlare(String url) {
        FlareSolverrInvoker.FlareResult result = flareSolverr.get(url);
        return Jsoup.parse(result.html(), url);
    }

    /**
     * Walks every {@code <td>label</td><td>value</td>} pair and stores
     * accent-stripped, lowercase labels — same defensive pattern as
     * {@code PlacaFipeScraperClient} but inlined to keep this fonte
     * independent of that cascade's signatures.
     */
    private Map<String, String> scanLabelValuePairs(Document doc) {
        Map<String, String> map = new LinkedHashMap<>();
        Elements rows = doc.select("tr");
        for (Element row : rows) {
            Elements tds = row.select("> td");
            if (tds.size() >= 2) {
                String label = normalizeKey(tds.get(0).text());
                String value = tds.get(1).text().trim();
                if (!label.isEmpty() && !value.isEmpty()) {
                    map.putIfAbsent(label, value);
                }
            }
        }
        return map;
    }

    private static String normalizeKey(String text) {
        if (text == null) return "";
        String stripped = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replace(":", "")
                .trim();
        return stripped.replaceAll("\\s+", " ");
    }

    private String formatBaseline(String marca, String modelo, String ano, String municipio, String uf) {
        StringBuilder sb = new StringBuilder();
        if (marca != null) sb.append(marca);
        if (modelo != null) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(modelo);
        }
        if (ano != null) sb.append(" - ").append(ano);
        if (municipio != null) {
            if (!sb.isEmpty()) sb.append(" — ");
            sb.append(municipio);
            if (uf != null) sb.append('/').append(uf);
        } else if (uf != null) {
            sb.append(" — ").append(uf);
        }
        sb.append(" · registro localizado, sem marcador de leilão/sinistro nesta fonte.");
        return sb.toString();
    }
}
