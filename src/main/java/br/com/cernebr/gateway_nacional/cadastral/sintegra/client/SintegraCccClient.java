package br.com.cernebr.gateway_nacional.cadastral.sintegra.client;

import br.com.cernebr.gateway_nacional.cadastral.sintegra.dto.SintegraResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Provedor primário do Sintegra — Cadastro Centralizado de Contribuintes do
 * SVRS (Sefaz Virtual do Rio Grande do Sul), que consolida IEs de
 * <a href="https://www.sefaz.rs.gov.br/sintegra/">~22 UFs</a> em um único
 * formulário, sem CAPTCHA severo no caminho consulta-por-CNPJ.
 *
 * <p><b>Por que CCC e não cada SEFAZ individual:</b> 27 portais estaduais
 * têm 27 layouts e 27 CAPTCHAs distintos. O CCC/SVRS centraliza a
 * normalização e é a fonte oficial declarada pelo CONFAZ. Em troca, falta
 * cobertura para SP, MG e RJ (que mantêm seus próprios portais blindados);
 * para essas, o fallback agregador entra em ação.</p>
 *
 * <p><b>Parser:</b> tabela de resultado tem layout estável desde 2016.
 * Seletores ancorados em {@code th} pelo rótulo (não posição), tolerantes
 * a reordenação de colunas.</p>
 */
@Slf4j
@Component
public class SintegraCccClient implements SintegraClient {

    public static final String PROVIDER_NAME = "CCC-SVRS";

    private static final String PATH = "/Servicos/CCC-Sintegra/Consulta";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0 Safari/537.36 gateway-nacional/1.0";

    private static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale.Builder().setLanguage("pt").setRegion("BR").build());

    private final String baseUrl;
    private final int timeoutMs;

    public SintegraCccClient(
            @Value("${gateway.sintegra.ccc.base-url:https://www.sefaz.rs.gov.br}") String baseUrl,
            @Value("${gateway.sintegra.ccc.timeout-ms:8000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
    }

    @Override
    @CircuitBreaker(name = "sintegraCccCB", fallbackMethod = "fallback")
    public Optional<SintegraResponse> fetch(String cnpj, String uf) {
        try {
            Connection conn = Jsoup.connect(baseUrl + PATH)
                    .userAgent(USER_AGENT)
                    .timeout(timeoutMs)
                    .method(Connection.Method.POST)
                    .data("cnpj", cnpj);

            if (uf != null && !uf.isBlank()) {
                conn = conn.data("uf", uf.toUpperCase(Locale.ROOT));
            }

            Connection.Response resp = conn.execute();
            Document doc = resp.parse();
            return parseResultado(cnpj, uf, doc);

        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha de rede ao consultar CCC/SVRS: " + ex.getMessage(), ex);
        }
    }

    private Optional<SintegraResponse> parseResultado(String cnpj, String uf, Document doc) {
        if (doc.selectFirst(".mensagem-erro, .alerta-nao-encontrado") != null) {
            return Optional.empty();
        }
        Element tabela = doc.selectFirst("table.tbl-resultado, table#dadosCcc");
        if (tabela == null) {
            return Optional.empty();
        }
        Elements linhas = tabela.select("tr");
        if (linhas.isEmpty()) {
            return Optional.empty();
        }

        String ie = valorPorRotulo(tabela, "Inscrição Estadual");
        String ufResp = valorPorRotulo(tabela, "UF");
        String situacao = mapSituacao(valorPorRotulo(tabela, "Situação Cadastral"));
        String dataSituacao = normalizeDate(valorPorRotulo(tabela, "Data da Situação"));
        String regime = mapRegime(valorPorRotulo(tabela, "Regime de Apuração"));

        if (ie == null || ie.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new SintegraResponse(
                cnpj,
                ie,
                ufResp != null && !ufResp.isBlank() ? ufResp : (uf != null ? uf.toUpperCase(Locale.ROOT) : null),
                situacao,
                dataSituacao,
                regime,
                PROVIDER_NAME
        ));
    }

    private static String valorPorRotulo(Element tabela, String rotulo) {
        for (Element tr : tabela.select("tr")) {
            Element th = tr.selectFirst("th");
            Element td = tr.selectFirst("td");
            if (th != null && td != null && th.text().trim().equalsIgnoreCase(rotulo)) {
                return td.text().trim();
            }
        }
        return null;
    }

    private static String mapSituacao(String raw) {
        if (raw == null) return null;
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("ATIV")) return "ATIVA";
        if (upper.contains("SUSPEN")) return "SUSPENSA";
        if (upper.contains("BAIX")) return "BAIXADA";
        if (upper.contains("INAPT")) return "INAPTA";
        if (upper.contains("NULA")) return "NULA";
        return upper;
    }

    private static String mapRegime(String raw) {
        if (raw == null) return null;
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("SIMPLES")) return "SIMPLES_NACIONAL";
        if (upper.contains("MEI")) return "MEI";
        if (upper.contains("ESTIM")) return "ESTIMATIVA";
        if (upper.contains("SUBSTITU")) return "SUBSTITUTO";
        if (upper.contains("NORMAL")) return "NORMAL";
        return upper;
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
    private Optional<SintegraResponse> fallback(String cnpj, String uf, Throwable cause) {
        log.warn("Sintegra-CCC fallback for cnpj={} uf={} cause={}", cnpj, uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "CCC/SVRS indisponível ou Circuit Breaker aberto.", cause);
    }
}
