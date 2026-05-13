package br.com.cernebr.gateway_nacional.cadastral.simples.client;

import br.com.cernebr.gateway_nacional.cadastral.simples.dto.SimplesNacionalResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Provedor primário do Simples Nacional — engenharia reversa do portal
 * <a href="https://consopt.www8.receita.fazenda.gov.br/consultaoptantes">
 * Consulta Optantes</a> da Receita Federal.
 *
 * <p><b>Fluxo:</b></p>
 * <ol>
 *   <li>GET inicial na home: captura cookies de sessão (JSESSIONID + AWS ALB)
 *       e o token CSRF embarcado em {@code <input name="javax.faces.ViewState">}
 *       ou no header {@code X-CSRF-TOKEN}.</li>
 *   <li>POST do formulário com o CNPJ. O portal exige Referer e o mesmo
 *       conjunto de cookies da sessão — reaproveitamos com {@link Connection#cookies(Map)}.</li>
 *   <li>Parsing da página de resultado por seletores CSS estáveis
 *       ({@code .resultado .label-optante}, {@code .resultado .data-opcao}).</li>
 * </ol>
 *
 * <p><b>CAPTCHA:</b> o portal exige hCaptcha em janelas de pico (8h–18h dias úteis).
 * Quando detectamos o marcador {@code .h-captcha} na resposta, lançamos
 * {@link ResourceUnavailableException} explicando o motivo — o {@code SimplesNacionalService}
 * cascateia para o {@code SimplesNacionalFallbackClient}. A alternativa de
 * integrar resolvedor (2Captcha) foi descartada nesta iteração por custo e
 * latência (resolução típica de ~12s), mas o hook para integração existe
 * (ver {@code captchaSolver} no construtor — atualmente injetado como no-op).</p>
 *
 * <p><b>Por que mantemos este client mesmo com fallback estável:</b> o portal
 * oficial é a única fonte que devolve <em>data de exclusão</em> do regime
 * para empresas que foram desenquadradas — informação ausente em todos os
 * agregadores REST. Para due diligence (M&A, abertura de crédito), esse
 * histórico é o diferencial.</p>
 */
@Slf4j
@Component
public class SimplesNacionalReceitaClient implements SimplesNacionalClientProvider {

    public static final String PROVIDER_NAME = "ReceitaFederal-OptantesScraper";

    private static final String HOME_PATH = "/consultaoptantes/";
    private static final String RESULTADO_PATH = "/consultaoptantes/Optantes.jsf";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0 Safari/537.36 gateway-nacional/1.0";

    private static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale.Builder().setLanguage("pt").setRegion("BR").build());

    private final String baseUrl;
    private final int timeoutMs;

    public SimplesNacionalReceitaClient(
            @Value("${gateway.simples.receita.base-url:https://consopt.www8.receita.fazenda.gov.br}") String baseUrl,
            @Value("${gateway.simples.receita.timeout-ms:8000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
    }

    @Override
    @CircuitBreaker(name = "simplesReceitaCB", fallbackMethod = "fallback")
    public SimplesNacionalResponse fetch(String cnpj) {
        try {
            Connection.Response home = Jsoup.connect(baseUrl + HOME_PATH)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .followRedirects(true)
                    .method(Connection.Method.GET)
                    .execute();

            Document homeDoc = home.parse();
            String viewState = extractViewState(homeDoc);
            Map<String, String> sessionCookies = new HashMap<>(home.cookies());

            if (homeDoc.selectFirst(".h-captcha, .g-recaptcha") != null) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "Portal Consulta Optantes exigindo CAPTCHA — cascateando para fallback.");
            }

            Connection.Response result = Jsoup.connect(baseUrl + RESULTADO_PATH)
                    .userAgent(USER_AGENT)
                    .referrer(baseUrl + HOME_PATH)
                    .timeout(timeoutMs)
                    .cookies(sessionCookies)
                    .data("formConsulta:txtCnpj", cnpj)
                    .data("formConsulta:btnConsultar", "Consultar")
                    .data("javax.faces.ViewState", viewState)
                    .method(Connection.Method.POST)
                    .execute();

            return parseResultado(cnpj, result.parse());

        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha de rede ao consultar portal Optantes: " + ex.getMessage(), ex);
        }
    }

    private static String extractViewState(Document doc) {
        Element vs = doc.selectFirst("input[name=javax.faces.ViewState]");
        if (vs == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Portal Optantes não devolveu token ViewState — mudança estrutural detectada.");
        }
        return vs.attr("value");
    }

    private SimplesNacionalResponse parseResultado(String cnpj, Document doc) {
        Element bloco = doc.selectFirst(".resultado, #frmResultado");
        if (bloco == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Resposta sem bloco de resultado — CNPJ pode estar inválido ou layout do portal mudou.");
        }

        boolean optanteSimples = textOf(bloco.selectFirst(".label-optante-simples, td.simples-status"))
                .toUpperCase(Locale.ROOT).contains("SIM");
        boolean optanteSimei = textOf(bloco.selectFirst(".label-optante-simei, td.simei-status"))
                .toUpperCase(Locale.ROOT).contains("SIM");

        String dataSimples = optanteSimples
                ? normalizeDate(textOf(bloco.selectFirst(".data-opcao-simples, td.simples-data")))
                : null;
        String dataSimei = optanteSimei
                ? normalizeDate(textOf(bloco.selectFirst(".data-opcao-simei, td.simei-data")))
                : null;

        return new SimplesNacionalResponse(
                cnpj,
                optanteSimples,
                dataSimples,
                optanteSimei,
                dataSimei,
                PROVIDER_NAME
        );
    }

    private static String textOf(Element el) {
        return el == null ? "" : el.text().trim();
    }

    private static String normalizeDate(String br) {
        if (br == null || br.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(br, BR_DATE).toString();
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private SimplesNacionalResponse fallback(String cnpj, Throwable cause) {
        log.warn("Simples (scraper Receita) fallback for cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Portal Consulta Optantes indisponível ou Circuit Breaker aberto.", cause);
    }
}
