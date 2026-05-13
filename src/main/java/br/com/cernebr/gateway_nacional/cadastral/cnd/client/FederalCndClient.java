package br.com.cernebr.gateway_nacional.cadastral.cnd.client;

import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndFederal;
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
import java.util.Locale;

/**
 * Provedor da Certidão Conjunta Negativa de Débitos relativos a Tributos
 * Federais e Dívida Ativa da União, emitida pelo portal
 * <a href="https://solucoes.receita.fazenda.gov.br/Servicos/certidaointernet/PJ/Emitir">
 * Emissão de Certidão PJ</a> da Receita Federal / PGFN.
 *
 * <p><b>Fluxo:</b> diferente do FGTS, o portal PGFN/RFB aceita um GET parametrizado
 * com o CNPJ. A resposta volta em HTML com o número de controle, data de
 * emissão/validade e link permanente para o PDF de verificação. Não há
 * ViewState — a complexidade aqui é parsear estados como "Regular" vs
 * "Pendência de regularização" vs "Existência de débitos".</p>
 *
 * <p><b>Por que a Certidão Conjunta é o padrão:</b> em 2014 a PGFN e a RFB
 * unificaram a emissão (Portaria Conjunta 1.751/2014), eliminando a
 * necessidade de duas certidões separadas. Nosso client devolve um único
 * status conjunto — atende o caso 99%, simplifica o consumo no front e o
 * armazenamento histórico.</p>
 */
@Slf4j
@Component
public class FederalCndClient {

    public static final String PROVIDER_NAME = "PGFN-Receita-Federal";

    private static final String PATH = "/Servicos/certidaointernet/PJ/Emitir";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0 Safari/537.36 gateway-nacional/1.0";

    private static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale.Builder().setLanguage("pt").setRegion("BR").build());

    private final String baseUrl;
    private final int timeoutMs;

    public FederalCndClient(
            @Value("${gateway.cnd.federal.base-url:https://solucoes.receita.fazenda.gov.br}") String baseUrl,
            @Value("${gateway.cnd.federal.timeout-ms:8000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
    }

    @CircuitBreaker(name = "federalCndCB", fallbackMethod = "fallback")
    public CndFederal fetch(String cnpj) {
        try {
            Connection.Response resp = Jsoup.connect(baseUrl + PATH)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .data("ni", cnpj)
                    .data("tipoNI", "2")
                    .data("passo", "2")
                    .method(Connection.Method.POST)
                    .execute();

            return parseResultado(resp.parse());

        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha de rede ao consultar PGFN/Receita: " + ex.getMessage(), ex);
        }
    }

    private CndFederal parseResultado(Document doc) {
        Element bloco = doc.selectFirst("#divResultado, .resultadoCertidao");
        if (bloco == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Resposta sem bloco de resultado — CNPJ pode estar inválido ou layout mudou.");
        }

        String situacao = textByLabel(bloco, "Resultado");
        String numero = textByLabel(bloco, "Código de Controle");
        String emissao = textByLabel(bloco, "Data de Emissão");
        String validade = textByLabel(bloco, "Válida até");

        Element pdfLink = bloco.selectFirst("a[href*=PDF.aspx], a[href$=.pdf]");
        String pdfUrl = null;
        if (pdfLink != null) {
            String href = pdfLink.attr("href");
            pdfUrl = href.startsWith("http") ? href : baseUrl + (href.startsWith("/") ? href : "/" + href);
        }

        return new CndFederal(
                mapStatus(situacao),
                normalizeDate(emissao),
                normalizeDate(validade),
                pdfUrl,
                numero,
                null
        );
    }

    private static String textByLabel(Element bloco, String label) {
        for (Element row : bloco.select("tr, .linhaResultado, p")) {
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
        if (upper.contains("NEGATIVA") && upper.contains("EFEITO")) return "POSITIVA_COM_EFEITO_NEGATIVO";
        if (upper.contains("REGULAR") || upper.contains("NEGATIVA")) return "NEGATIVA";
        if (upper.contains("PENDÊNCIA") || upper.contains("PENDENCIA")) return "POSITIVA_COM_EFEITO_NEGATIVO";
        if (upper.contains("POSITIVA") || upper.contains("DÉBITO") || upper.contains("DEBITO")) return "POSITIVA";
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
    private CndFederal fallback(String cnpj, Throwable cause) {
        log.warn("Federal CND fallback for cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "PGFN/Receita indisponível ou Circuit Breaker aberto: " + cause.getMessage(), cause);
    }
}
