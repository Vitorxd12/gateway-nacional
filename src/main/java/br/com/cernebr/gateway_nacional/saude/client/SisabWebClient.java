package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.dto.ProducaoSisabResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SISAB validation report scraper, mediated by the FlareSolverr sidecar.
 *
 * <h2>Why FlareSolverr is mandatory here</h2>
 * <p>SISAB is a JSF/PrimeFaces application protected by an aggressive WAF.
 * Even after replicating the full PrimeFaces AJAX envelope (Faces-Request,
 * partial.ajax payload, ViewState, JSESSIONID continuity), gov.br rejects
 * the call from a Java process. FlareSolverr resolves this by driving a
 * headless Chromium that the WAF accepts as a real browser.</p>
 *
 * <h2>GET → POST flow</h2>
 * <ol>
 *   <li>FlareSolverr {@code request.get} loads the report XHTML and returns
 *       both the rendered HTML and the cookies the WAF set after challenge —
 *       crucial because JSF binds the {@code javax.faces.ViewState} to that
 *       JSESSIONID;</li>
 *   <li>The {@code javax.faces.ViewState} is parsed out of the HTML;</li>
 *   <li>FlareSolverr {@code request.post} replays the cookies, the ViewState,
 *       and the standard partial-AJAX form fields ({@code source}, {@code execute},
 *       {@code render}) so the JSF backend treats the request as a partial
 *       submission triggered by the "verTela" button.</li>
 * </ol>
 *
 * <p>When {@code gateway.flaresolverr.url} is empty, the call short-circuits
 * with the canonical {@code "Esta rota exige a ativação do sidecar
 * FlareSolverr..."} message — fast 503 instead of futile direct attempts.</p>
 */
@Slf4j
@Component
public class SisabWebClient implements SisabClientProvider {

    public static final String PROVIDER_NAME = "SISAB";
    static final String FLARE_REQUIRED_MESSAGE =
            "Esta rota exige a ativação do sidecar FlareSolverr devido ao WAF governamental.";

    private static final String VIEW_STATE_KEY = "javax.faces.ViewState";

    private final String reportUrl;
    private final FlareSolverrInvoker flareSolverr;

    public SisabWebClient(@Value("${gateway.saude.sisab.base-url:https://sisab.saude.gov.br}") String baseUrl,
                          @Value("${gateway.saude.sisab.report-path:/paginas/acessoRestrito/relatorio/federal/envio/RelValidacao.xhtml}") String reportPath,
                          FlareSolverrInvoker flareSolverr) {
        this.reportUrl = baseUrl + reportPath;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "sisabScraperCB", fallbackMethod = "fallback")
    public List<ProducaoSisabResponse> fetchProducao(String ibge6, int ano, int mes) {
        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }
        String uf = IbgeUfLookup.ufFromIbge(ibge6);
        if (uf == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "SISAB: IBGE inválido para derivação de UF: " + ibge6);
        }
        String competencia = String.format(Locale.ROOT, "%02d/%04d", mes, ano);

        // JSF/PrimeFaces requires a single Chromium context across GET and
        // POST: the ViewState captured on the GET binds to the JSESSIONID
        // and the JS fingerprint of that context. Without a persistent
        // session, the POST trips ViewExpiredException. So we book a
        // FlareSolverr session up-front and tear it down in the finally.
        String sessionId = flareSolverr.createSession();
        try {
            FlareSolverrInvoker.FlareResult initial = flareSolverr.getInSession(reportUrl, sessionId);
            Document initialDoc = Jsoup.parse(initial.body(), reportUrl);
            String viewState = extractViewState(initialDoc);
            if (viewState == null) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "SISAB retornou página sem ViewState — layout alterado ou bloqueio anti-bot.");
            }

            Map<String, String> formData = buildFormData(uf, ibge6, competencia, viewState);
            FlareSolverrInvoker.FlareResult result = flareSolverr.postInSession(reportUrl, formData, sessionId);
            // Chromium wraps non-HTML payloads (JSF AJAX returns XML) in its
            // own <html><body> viewer template. jsonBody() peels Chrome's
            // <pre> wrapper for JSON; for XML the rendered tree is best
            // re-parsed with Jsoup directly on the raw body — Jsoup ignores
            // the Chrome chrome cleanly.
            Document resultDoc = Jsoup.parse(result.body(), reportUrl);

            List<ProducaoSisabResponse> rows = extractRows(resultDoc, ibge6);
            if (rows.isEmpty()) {
                String body = result.body();
                if (body != null && body.contains("ViewExpiredException")) {
                    throw new ResourceUnavailableException(PROVIDER_NAME,
                            "SISAB recusou a submissão (ViewExpiredException) mesmo com session FlareSolverr — "
                                    + "verifique conectividade e logs do FlareSolverr.");
                }
                // Após validação em produção: o form de Validação SISAB depende
                // de uma cascata de AJAX listeners (unidGeo→regioes→municipios→
                // competencia) onde cada step rotaciona o ViewState. O submit
                // único, mesmo com session, retorna partial-response com
                // ViewRoot rerenderizado mas sem a tabela de resultados —
                // o servidor não consegue resolver UF/Município sem o
                // cascading que popula esses selects via AJAX.
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "SISAB respondeu mas a tabela de validação não foi gerada — o form exige a cascata AJAX completa "
                                + "(unidGeo → UF → Município → competência). Implementação de submissão única não suporta "
                                + "esse fluxo; um sidecar Selenium dedicado seria necessário para esta rota.");
            }
            log.info("SISAB scraped {} rows for IBGE={} competencia={}", rows.size(), ibge6, competencia);
            return rows;
        } finally {
            flareSolverr.destroySession(sessionId);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<ProducaoSisabResponse> fallback(String ibge6, int ano, int mes, Throwable cause) {
        log.warn("SISAB fallback triggered for IBGE={} {}/{} cause={}", ibge6, mes, ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "SISAB indisponível ou Circuit Breaker aberto.", cause);
    }

    private String extractViewState(Document doc) {
        Element vs = doc.selectFirst("input[name=" + VIEW_STATE_KEY + "]");
        if (vs == null) return null;
        String value = vs.attr("value");
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Single-shot form fill emulating a full PrimeFaces AJAX submission.
     * Functional fields ({@code unidGeo}, {@code estadoMunicipio},
     * {@code municipios}, {@code competencia}) carry the user filters; the
     * JSF AJAX framework fields ({@code javax.faces.partial.ajax},
     * {@code source}, {@code execute}, {@code render}, {@code ViewState})
     * declare the request as a partial submit triggered by the "verTela"
     * button — the same envelope a real browser sends. No pre-filter on
     * {@code validacao}: the result must include both Aprovado and Reprovado
     * rows so consumers can audit suspensions.
     */
    private Map<String, String> buildFormData(String uf, String ibge6, String competencia, String viewState) {
        Map<String, String> form = new HashMap<>();
        form.put("unidGeo", "MUNICIPIO");
        form.put("estadoMunicipio", uf);
        form.put("municipios", ibge6);
        form.put("competencia", competencia);
        form.put("verTela", "verTela");
        form.put("javax.faces.partial.ajax", "true");
        form.put("javax.faces.source", "verTela");
        form.put("javax.faces.partial.execute", "@all");
        form.put("javax.faces.partial.render", "@all");
        form.put(VIEW_STATE_KEY, viewState);
        return form;
    }

    /**
     * Defensive HTML table parser. Looks for any {@code <table>} whose
     * header row contains the canonical SISAB columns (CNES, INE, VALIDAÇÃO).
     * Maps each data row to {@link ProducaoSisabResponse} using header-text
     * matching (no positional assumption — survives column reorder).
     */
    private List<ProducaoSisabResponse> extractRows(Document doc, String ibge6) {
        List<ProducaoSisabResponse> rows = new ArrayList<>();
        Elements tables = doc.select("table");
        for (Element table : tables) {
            int idxIbge = -1, idxCnes = -1, idxIne = -1, idxValidacao = -1;

            Elements headers = table.select("thead tr").first() != null
                    ? table.select("thead tr").first().select("th")
                    : table.select("tr").first() != null ? table.select("tr").first().select("th") : new Elements();
            if (headers.isEmpty()) continue;

            for (int i = 0; i < headers.size(); i++) {
                String norm = headers.get(i).text().trim().toUpperCase(Locale.ROOT);
                if (norm.contains("IBGE")) idxIbge = i;
                else if (norm.contains("CNES")) idxCnes = i;
                else if (norm.equals("INE") || norm.contains("EQUIPE")) idxIne = (idxIne < 0 ? i : idxIne);
                else if (norm.contains("VALIDAC")) idxValidacao = i;
            }
            if (idxCnes < 0 || idxIne < 0 || idxValidacao < 0) continue;

            Elements bodyRows = table.select("tbody tr");
            if (bodyRows.isEmpty()) bodyRows = table.select("tr");
            for (int r = 0; r < bodyRows.size(); r++) {
                Element row = bodyRows.get(r);
                Elements cells = row.select("td");
                if (cells.size() < Math.max(Math.max(idxCnes, idxIne), idxValidacao) + 1) continue;
                try {
                    String ibgeCell = idxIbge >= 0 ? cells.get(idxIbge).text().trim() : ibge6;
                    String cnes = cells.get(idxCnes).text().trim();
                    String ine = cells.get(idxIne).text().trim();
                    String validacao = cells.get(idxValidacao).text().trim();
                    if (cnes.isEmpty() || ine.isEmpty()) continue;
                    rows.add(new ProducaoSisabResponse(
                            ibgeCell.isEmpty() ? ibge6 : ibgeCell,
                            cnes,
                            ine,
                            validacao.isEmpty() ? "DESCONHECIDO" : validacao
                    ));
                } catch (Exception ex) {
                    log.debug("SISAB skipped malformed row {}: {}", r, ex.toString());
                }
            }
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        return rows;
    }
}
