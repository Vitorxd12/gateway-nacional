package br.com.cernebr.gateway_nacional.licitacoes.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.licitacoes.captcha.CaptchaSolverEngine;
import br.com.cernebr.gateway_nacional.licitacoes.dto.AnexoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoDetalheDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoResumoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Modalidade;
import br.com.cernebr.gateway_nacional.licitacoes.dto.OrgaoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Portal;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente da Bolsa de Licitações e Leilões (BLL — bllcompras.com).
 *
 * <p><b>Estratégia de acesso (auditado 2026-05):</b>
 * <ol>
 *   <li><b>Listagem</b>: a página pública
 *       {@code /Process/ProcessSearchPublic?param1=0} renderiza 100 processos
 *       ativos sem exigir captcha (SSR puro). O scraping é resiliente e
 *       suficiente para o caso de uso de agregação.</li>
 *   <li><b>Listagem ampliada</b>: quando {@link CaptchaSolverEngine#available()}
 *       é {@code true} e há filtro de UF, o cliente resolve o reCAPTCHA v2
 *       invisível (sitekey {@value #BLL_SITEKEY}) via CapSolver/2Captcha e
 *       chama {@code /Process/GetProcessByParams} para obter resultados
 *       filtrados pelo servidor.</li>
 *   <li><b>Detalhe</b>: o identificador de cada processo é um Base64URL de
 *       {@code orgName|numero}. No detalhe:
 *       <ol type="a">
 *         <li>Re-escaneia a listagem para localizar o {@code param1} corrente.</li>
 *         <li>GET {@code /Process/ProcessView?param1=…} para extrair os tokens
 *             internos gerados por essa requisição.</li>
 *         <li>POST {@code /Process/ProcessInformation} (sem captcha) → HTML com
 *             todos os campos do processo.</li>
 *         <li>POST {@code /Process/ProcessFiles} (sem captcha) → HTML com lista
 *             de anexos e URLs de download direto.</li>
 *       </ol>
 *   </li>
 * </ol>
 *
 * <p><b>Nota sobre os tokens {@code [gkz]}:</b> o BLL gera tokens opacos
 * (criptografados) em cada renderização de página — não são IDs estáveis.
 * O identificador externo estável é o par {@code orgName|numero} (Base64URL).
 */
@Slf4j
@Component
public class BllComprasClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "BLL-Compras";

    static final String BLL_SITEKEY = "6LdpKvsmAAAAAA4rzH5iQNswgItyulQ1J2HQ1FkK";

    /** Retorna o sitekey do reCAPTCHA para este portal. Subclasses sobrescrevem. */
    protected String siteKey() {
        return BLL_SITEKEY;
    }

    private static final String SEARCH_PATH = "/Process/ProcessSearchPublic?param1=0";
    private static final String PROCESS_VIEW_PATH = "/Process/ProcessView";
    private static final String PROCESS_INFO_PATH = "/Process/ProcessInformation";
    private static final String PROCESS_FILES_PATH = "/Process/ProcessFiles";
    private static final String GET_PARAMS_PATH = "/Process/GetProcessByParams";

    private static final DateTimeFormatter BLL_DT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // Regex: extrai o param1 interno para ProcessInformation embutido no JS do ProcessView
    private static final Pattern INFO_PARAM_RE = Pattern.compile(
            "ProcessInformation\\?param1='\\s*\\+\\s*'([^']+)'");
    // Regex: extrai o param1 interno para ProcessFiles do onclick doAction
    private static final Pattern FILES_PARAM_RE = Pattern.compile(
            "'Process','ProcessFiles',\\s*\\['([^']+)'\\]");

    // BLL estado IDs (extraídos do select fkState da página de busca)
    private static final Map<String, String> UF_TO_STATE_ID = Map.ofEntries(
            Map.entry("AC", "1"), Map.entry("AL", "2"), Map.entry("AM", "4"),
            Map.entry("AP", "3"), Map.entry("BA", "5"), Map.entry("CE", "6"),
            Map.entry("DF", "7"), Map.entry("ES", "8"), Map.entry("GO", "9"),
            Map.entry("MA", "10"), Map.entry("MG", "27"), Map.entry("MS", "12"),
            Map.entry("MT", "11"), Map.entry("PA", "13"), Map.entry("PB", "14"),
            Map.entry("PE", "16"), Map.entry("PI", "17"), Map.entry("PR", "15"),
            Map.entry("RJ", "18"), Map.entry("RN", "19"), Map.entry("RO", "21"),
            Map.entry("RR", "22"), Map.entry("RS", "20"), Map.entry("SC", "23"),
            Map.entry("SE", "25"), Map.entry("SP", "24"), Map.entry("TO", "26")
    );

    private final String baseUrl;
    private final String userAgent;
    private final int timeoutMs;
    private final CaptchaSolverEngine captchaSolver;
    private final ObjectMapper objectMapper;

    public BllComprasClient(
            @Value("${gateway.licitacoes.bll.base-url:https://bllcompras.com}") String baseUrl,
            @Value("${gateway.licitacoes.bll.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36}") String userAgent,
            @Value("${gateway.licitacoes.bll.timeout-millis:12000}") int timeoutMs,
            CaptchaSolverEngine captchaSolver) {
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
        this.captchaSolver = captchaSolver;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Portal portal() {
        return Portal.BLL;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "bllCB", fallbackMethod = "fallbackListar")
    public List<LicitacaoResumoDTO> listarAtivas(String uf, String modalidade) {
        // Passo 1: scraping da página inicial — sempre, sem captcha
        List<ProcessRow> rows = scrapeListingPage(baseUrl + SEARCH_PATH);

        // Passo 2: se captcha disponível e UF especificada, busca filtrada via AJAX
        if (captchaSolver.available() && uf != null && !uf.isBlank()) {
            rows = supplementWithFilteredSearch(rows, uf);
        }

        // Passo 3: filtro em Java + mapeamento DTO
        return rows.stream()
                .filter(r -> matchesUf(r.cidadeUf, uf))
                .map(r -> toResumo(r))
                .filter(dto -> matchesModalidade(dto, modalidade))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "bllCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        String decoded = decodeIdentifier(identificador);
        if (decoded == null || !decoded.contains("|")) {
            log.debug("[BLL] Identificador inválido: {}", identificador);
            return Optional.empty();
        }
        int sep = decoded.indexOf('|');
        String orgName = decoded.substring(0, sep);
        String numero = decoded.substring(sep + 1);

        // Localiza o processo na listagem corrente para obter o param1 do BLL
        String listingParam1 = findParam1InListing(orgName, numero);
        if (listingParam1 == null) {
            log.debug("[BLL] Processo não encontrado na listagem corrente: {}|{}", orgName, numero);
            return Optional.empty();
        }

        // Carrega ProcessView para extrair os tokens internos (por requisição)
        String processViewHtml;
        try {
            processViewHtml = Jsoup.connect(baseUrl + PROCESS_VIEW_PATH)
                    .data("param1", listingParam1)
                    .method(Connection.Method.GET)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(true)
                    .execute()
                    .body();
        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao carregar ProcessView: " + ex.getMessage(), ex);
        }

        String infoParam = extractParam(processViewHtml, INFO_PARAM_RE);
        String filesParam = extractParam(processViewHtml, FILES_PARAM_RE);

        ProcessInfo info = null;
        if (infoParam != null) {
            info = fetchProcessInfo(infoParam);
        }

        List<AnexoDTO> anexos = List.of();
        if (filesParam != null) {
            anexos = fetchProcessFiles(filesParam);
        }

        return Optional.of(buildDetalheDto(identificador, orgName, numero,
                listingParam1, info, anexos));
    }

    // ─────────────────────────── scraping helpers ───────────────────────────

    List<ProcessRow> scrapeListingPage(String url) {
        try {
            Connection.Response resp = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(true)
                    .followRedirects(false)
                    .execute();
            Document doc = resp.parse();
            return parseTableRows(doc);
        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao carregar página de listagem BLL: " + ex.getMessage(), ex);
        }
    }

    private List<ProcessRow> parseTableRows(Document doc) {
        List<ProcessRow> result = new ArrayList<>();
        Elements rows = doc.select("#tableProcessDataBody tr");
        for (Element row : rows) {
            Elements tds = row.select("td");
            if (tds.size() < 8) continue;
            Element linkEl = tds.get(0).selectFirst("a[href]");
            if (linkEl == null) continue;
            String href = linkEl.attr("href");
            // Extrair o valor raw de param1 do href
            String param1 = extractQueryParam(href, "param1");
            if (param1 == null) continue;
            result.add(new ProcessRow(
                    param1,
                    tds.get(1).text(),           // orgName / Promotor
                    tds.get(2).text(),           // numero
                    tds.get(3).text(),           // modalidadeRaw
                    tds.get(4).text(),           // cidadeUf (ex.: CANUDOS-BA)
                    tds.get(5).text(),           // situacao
                    tds.get(6).text(),           // publicacao (dd/MM/yyyy HH:mm)
                    tds.get(7).text()            // disputa (dd/MM/yyyy HH:mm)
            ));
        }
        return result;
    }

    private List<ProcessRow> supplementWithFilteredSearch(List<ProcessRow> existing, String uf) {
        String stateId = UF_TO_STATE_ID.getOrDefault(uf.toUpperCase(Locale.ROOT), "");
        if (stateId.isBlank()) {
            log.debug("[BLL] UF '{}' não mapeada para fkState, usando listagem inicial.", uf);
            return existing;
        }
        Optional<String> token = captchaSolver.solveV2(siteKey(), baseUrl + SEARCH_PATH);
        if (token.isEmpty()) {
            log.warn("[BLL] Captcha solver não retornou token; usando listagem inicial.");
            return existing;
        }
        try {
            Connection.Response resp = Jsoup.connect(baseUrl + GET_PARAMS_PATH)
                    .method(Connection.Method.POST)
                    .data("Organization", "")
                    .data("Number", "")
                    .data("City", "")
                    .data("fkState", stateId)
                    .data("fkModality", "0")
                    .data("fkStatus", "0")
                    .data("fkDisputeKind", "0")
                    .data("DateStart", "")
                    .data("DateEnd", "")
                    .data("DateStartDispute", "")
                    .data("DateEndDispute", "")
                    .data("Offset", "0")
                    .data("token", token.get())
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", baseUrl + SEARCH_PATH)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(true)
                    .followRedirects(false)
                    .execute();

            if (resp.statusCode() == 302) {
                log.warn("[BLL] GetProcessByParams retornou redirect (captcha inválido ou expirado).");
                return existing;
            }

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> json = objectMapper.readValue(resp.body(), java.util.Map.class);
            String html = (String) json.get("html");
            if (html == null || html.isBlank()) return existing;

            Document doc = Jsoup.parseBodyFragment(html);
            List<ProcessRow> ajaxRows = parseTableRows(doc);
            if (ajaxRows.isEmpty()) return existing;

            // Mescla: remove duplicatas pelo par orgName+numero
            java.util.Set<String> seen = new java.util.HashSet<>();
            List<ProcessRow> merged = new ArrayList<>(ajaxRows);
            merged.forEach(r -> seen.add(r.orgName() + "|" + r.numero()));
            existing.stream()
                    .filter(r -> seen.add(r.orgName() + "|" + r.numero()))
                    .forEach(merged::add);
            log.debug("[BLL] Listagem AJAX: {} rows UF={}", ajaxRows.size(), uf);
            return merged;
        } catch (Exception ex) {
            log.warn("[BLL] Busca AJAX com captcha falhou: {}; fallback para listagem inicial.", ex.toString());
            return existing;
        }
    }

    private String findParam1InListing(String orgName, String numero) {
        List<ProcessRow> rows;
        try {
            rows = scrapeListingPage(baseUrl + SEARCH_PATH);
        } catch (Exception ex) {
            log.warn("[BLL] Falha ao re-escanear listagem: {}", ex.toString());
            return null;
        }
        for (ProcessRow r : rows) {
            if (r.orgName().equalsIgnoreCase(orgName) && r.numero().equalsIgnoreCase(numero)) {
                return r.param1();
            }
        }
        return null;
    }

    private ProcessInfo fetchProcessInfo(String infoParam) {
        try {
            Connection.Response resp = Jsoup.connect(baseUrl + PROCESS_INFO_PATH)
                    .method(Connection.Method.POST)
                    .data("param1", infoParam)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(true)
                    .execute();

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> json = objectMapper.readValue(resp.body(), java.util.Map.class);
            String html = (String) json.get("html");
            if (html == null) return null;

            Document doc = Jsoup.parseBodyFragment(html);
            return new ProcessInfo(
                    inputVal(doc, "Organization"),
                    inputVal(doc, "Number"),
                    inputVal(doc, "Modality"),
                    inputVal(doc, "Status"),
                    inputVal(doc, "PublicationTime"),
                    inputVal(doc, "ProposalReceivingStart"),
                    inputVal(doc, "ProposalAnalysisStart"),
                    inputVal(doc, "DisputeStart"),
                    inputVal(doc, "TotalBaseValue"),
                    textareaVal(doc, "ProductOrService")
            );
        } catch (Exception ex) {
            log.debug("[BLL] fetchProcessInfo falhou: {}", ex.toString());
            return null;
        }
    }

    private List<AnexoDTO> fetchProcessFiles(String filesParam) {
        try {
            Connection.Response resp = Jsoup.connect(baseUrl + PROCESS_FILES_PATH)
                    .method(Connection.Method.POST)
                    .data("param1", filesParam)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(true)
                    .execute();

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> json = objectMapper.readValue(resp.body(), java.util.Map.class);
            String html = (String) json.get("html");
            if (html == null) return List.of();

            Document doc = Jsoup.parseBodyFragment(html);
            List<AnexoDTO> files = new ArrayList<>();
            for (Element row : doc.select("tr")) {
                Elements tds = row.select("td");
                if (tds.size() < 3) continue;
                Element link = tds.get(2).selectFirst("a[href]");
                if (link == null) continue;
                String url = link.attr("href");
                String nome = tds.get(0).text().trim();
                if (nome.isBlank() || url.isBlank()) continue;
                files.add(new AnexoDTO(nome, url, guessMime(nome), null));
            }
            return files;
        } catch (Exception ex) {
            log.debug("[BLL] fetchProcessFiles falhou: {}", ex.toString());
            return List.of();
        }
    }

    // ─────────────────────────── DTO mappers ────────────────────────────────

    private LicitacaoResumoDTO toResumo(ProcessRow r) {
        String uf = extractUf(r.cidadeUf());
        String orgNome = r.orgName();
        String identificador = encodeIdentifier(orgNome, r.numero());
        return new LicitacaoResumoDTO(
                portal(),
                identificador,
                r.numero(),
                null,
                Modalidade.infer(r.modalidadeRaw()),
                uf,
                new OrgaoDTO(orgNome, null, null, null, extractCity(r.cidadeUf()), uf),
                parseDate(r.disputa()),
                null,
                null,
                baseUrl + PROCESS_VIEW_PATH + "?param1=" + urlEncode(r.param1())
        );
    }

    private LicitacaoDetalheDTO buildDetalheDto(String identificador, String orgName, String numero,
                                                String param1, ProcessInfo info, List<AnexoDTO> anexos) {
        String uf = null;
        String objeto = null;
        String modalidadeRaw = null;
        String situacao = null;
        OffsetDateTime publicacao = null, abertura = null, encerramento = null;
        BigDecimal valor = null;

        if (info != null) {
            uf = null; // BLL não devolve UF no ProcessInfo; mantemos null
            objeto = info.objeto();
            modalidadeRaw = info.modalidade();
            situacao = info.status();
            publicacao = parseDate(info.publicationTime());
            abertura = parseDate(info.disputeStart());
            encerramento = parseDate(info.proposalAnalysisStart());
            valor = parseBrlValue(info.totalBaseValue());
        }

        return new LicitacaoDetalheDTO(
                portal(),
                identificador,
                numero,
                objeto,
                Modalidade.infer(modalidadeRaw),
                modalidadeRaw,
                uf,
                new OrgaoDTO(orgName, null, null, null, null, uf),
                abertura,
                encerramento,
                publicacao,
                valor,
                baseUrl + PROCESS_VIEW_PATH + "?param1=" + urlEncode(param1),
                situacao,
                List.of(),
                anexos
        );
    }

    // ─────────────────────────── utility ────────────────────────────────────

    static String encodeIdentifier(String orgName, String numero) {
        String raw = orgName + "|" + numero;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeIdentifier(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String extractParam(String html, Pattern pattern) {
        if (html == null) return null;
        Matcher m = pattern.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String extractQueryParam(String url, String name) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return null;
        String query = url.substring(q + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq);
            if (k.equals(name)) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return part.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String extractUf(String cidadeUf) {
        if (cidadeUf == null || cidadeUf.isBlank()) return null;
        int dash = cidadeUf.lastIndexOf('-');
        if (dash >= 0 && dash < cidadeUf.length() - 1) {
            String candidate = cidadeUf.substring(dash + 1).trim();
            if (candidate.length() == 2) return candidate.toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static String extractCity(String cidadeUf) {
        if (cidadeUf == null || cidadeUf.isBlank()) return null;
        int dash = cidadeUf.lastIndexOf('-');
        return dash > 0 ? cidadeUf.substring(0, dash).trim() : cidadeUf;
    }

    private static boolean matchesUf(String cidadeUf, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String uf = extractUf(cidadeUf);
        return filter.equalsIgnoreCase(uf);
    }

    private static boolean matchesModalidade(LicitacaoResumoDTO dto, String filtro) {
        if (filtro == null || filtro.isBlank()) return true;
        return dto.modalidade() != null && dto.modalidade().slug().equalsIgnoreCase(filtro);
    }

    private static OffsetDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.trim(), BLL_DT)
                    .atOffset(ZoneOffset.of("-03:00"))
                    .withOffsetSameInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static BigDecimal parseBrlValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.replace("R$", "").trim()
                    .replace(".", "")     // separador de milhar BR
                    .replace(",", ".");    // separador decimal BR
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String inputVal(Document doc, String id) {
        Element el = doc.getElementById(id);
        return el != null ? el.val() : null;
    }

    private static String textareaVal(Document doc, String id) {
        Element el = doc.getElementById(id);
        return el != null ? el.text() : null;
    }

    private static String guessMime(String filename) {
        if (filename == null) return null;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private static String urlEncode(String raw) {
        try {
            return java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    // ─────────────────────────── fallbacks ──────────────────────────────────

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("[BLL] listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BLL indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("[BLL] detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BLL indisponível ou Circuit Breaker aberto.", cause);
    }

    // ─────────────────────────── inner types ────────────────────────────────

    record ProcessRow(
            String param1,
            String orgName,
            String numero,
            String modalidadeRaw,
            String cidadeUf,
            String situacao,
            String publicacao,
            String disputa
    ) {}

    record ProcessInfo(
            String organization,
            String number,
            String modalidade,
            String status,
            String publicationTime,
            String proposalReceivingStart,
            String proposalAnalysisStart,
            String disputeStart,
            String totalBaseValue,
            String objeto
    ) {}
}
