package br.com.cernebr.gateway_nacional.cadastral.cnd.client;

import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndFgts;
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
 * Provedor do CRF — Certificado de Regularidade do FGTS — emitido pelo portal
 * <a href="https://consulta-crf.caixa.gov.br/consultacrf/">Consulta Regularidade
 * do Empregador</a> da Caixa Econômica Federal.
 *
 * <p><b>Desafio técnico:</b> o portal é ASP.NET WebForms — toda interação é
 * um postback que carrega o estado da view nos hiddens {@code __VIEWSTATE},
 * {@code __EVENTVALIDATION} e {@code __VIEWSTATEGENERATOR}. Pular esse
 * handshake faz o servidor devolver erro genérico "Sessão Expirada".</p>
 *
 * <p><b>Fluxo:</b></p>
 * <ol>
 *   <li>GET na home: Jsoup parseia os 3 hiddens e captura cookies.</li>
 *   <li>POST do form: envia {@code mskCNPJ} + hiddens + cookies. ASP.NET valida
 *       a integridade do ViewState antes de processar.</li>
 *   <li>Parser HTML de resultado: extrai status do CRF, validade e URL do PDF
 *       (gerada com token de sessão de uso único — válido por ~5 minutos).</li>
 * </ol>
 *
 * <p><b>Por que NÃO usar Selenium/Playwright:</b> tentação clássica para
 * "tabs com ASP.NET", mas a pilha headless adiciona ~3s e ~250MB residentes
 * por instância. Jsoup + handshake manual processa em ~1.2s com pegada de
 * KB — o ROI fica óbvio para um endpoint que entra no hot path da malha
 * de licitações.</p>
 */
@Slf4j
@Component
public class FgtsCndClient {

    public static final String PROVIDER_NAME = "Caixa-CRF-FGTS";

    private static final String HOME_PATH = "/consultacrf/pages/consultaEmpregador.jsf";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0 Safari/537.36 gateway-nacional/1.0";

    private static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale.Builder().setLanguage("pt").setRegion("BR").build());

    private final String baseUrl;
    private final int timeoutMs;

    public FgtsCndClient(
            @Value("${gateway.cnd.fgts.base-url:https://consulta-crf.caixa.gov.br}") String baseUrl,
            @Value("${gateway.cnd.fgts.timeout-ms:8000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
    }

    @CircuitBreaker(name = "fgtsCndCB", fallbackMethod = "fallback")
    public CndFgts fetch(String cnpj) {
        try {
            Connection.Response home = Jsoup.connect(baseUrl + HOME_PATH)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .method(Connection.Method.GET)
                    .execute();

            Document homeDoc = home.parse();
            String viewState = readHidden(homeDoc, "__VIEWSTATE");
            String eventValidation = readHidden(homeDoc, "__EVENTVALIDATION");
            String viewStateGenerator = readHidden(homeDoc, "__VIEWSTATEGENERATOR");

            if (viewState == null || eventValidation == null) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "Portal Caixa não devolveu __VIEWSTATE/__EVENTVALIDATION — layout pode ter mudado.");
            }

            Map<String, String> sessionCookies = new HashMap<>(home.cookies());

            Connection.Response result = Jsoup.connect(baseUrl + HOME_PATH)
                    .userAgent(USER_AGENT)
                    .referrer(baseUrl + HOME_PATH)
                    .timeout(timeoutMs)
                    .cookies(sessionCookies)
                    .data("__VIEWSTATE", viewState)
                    .data("__EVENTVALIDATION", eventValidation)
                    .data("__VIEWSTATEGENERATOR", viewStateGenerator == null ? "" : viewStateGenerator)
                    .data("ctl00$ContentPlaceHolder1$txtInscricao1", cnpj)
                    .data("ctl00$ContentPlaceHolder1$btnConsultar", "Consultar Empregador")
                    .method(Connection.Method.POST)
                    .execute();

            return parseResultado(result.parse());

        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha de rede ao consultar portal Caixa CRF: " + ex.getMessage(), ex);
        }
    }

    private CndFgts parseResultado(Document doc) {
        Element bloco = doc.selectFirst(".dadosEmpregador, #ContentPlaceHolder1_pnlResultado");
        if (bloco == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Resposta sem bloco de resultado — sessão pode ter expirado ou layout mudou.");
        }

        String situacao = textByLabel(bloco, "Situação");
        String emissao = textByLabel(bloco, "Emissão");
        String validade = textByLabel(bloco, "Validade");
        String numero = textByLabel(bloco, "Número do CRF");

        Element pdfLink = bloco.selectFirst("a[href*=ImprimeCRF], a[href$=.pdf]");
        String pdfUrl = null;
        if (pdfLink != null) {
            String href = pdfLink.attr("href");
            pdfUrl = href.startsWith("http") ? href : baseUrl + (href.startsWith("/") ? href : "/" + href);
        }

        return new CndFgts(
                mapStatus(situacao),
                normalizeDate(emissao),
                normalizeDate(validade),
                pdfUrl,
                numero,
                null
        );
    }

    private static String readHidden(Document doc, String name) {
        Element el = doc.selectFirst("input[name=" + name + "]");
        return el == null ? null : el.attr("value");
    }

    private static String textByLabel(Element bloco, String label) {
        for (Element row : bloco.select("tr, .linhaInfo")) {
            String text = row.text();
            if (text.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) {
                int idx = text.toLowerCase(Locale.ROOT).indexOf(label.toLowerCase(Locale.ROOT));
                String after = text.substring(idx + label.length()).trim();
                if (after.startsWith(":")) {
                    after = after.substring(1).trim();
                }
                return after.split("\\s{2,}")[0].trim();
            }
        }
        return null;
    }

    private static String mapStatus(String raw) {
        if (raw == null) return "INDISPONIVEL";
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("REGULAR") || upper.contains("NEGATIVA")) return "NEGATIVA";
        if (upper.contains("IRREGULAR") || upper.contains("POSITIVA")) return "POSITIVA";
        return upper;
    }

    private static String normalizeDate(String br) {
        if (br == null || br.isBlank()) return null;
        try {
            return LocalDate.parse(br, BR_DATE).toString();
        } catch (Exception ex) {
            return null;
        }
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CndFgts fallback(String cnpj, Throwable cause) {
        log.warn("FGTS CRF fallback for cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Caixa CRF indisponível ou Circuit Breaker aberto: " + cause.getMessage(), cause);
    }
}
