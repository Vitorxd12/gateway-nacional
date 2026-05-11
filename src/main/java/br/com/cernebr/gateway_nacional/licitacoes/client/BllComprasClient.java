package br.com.cernebr.gateway_nacional.licitacoes.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.licitacoes.dto.AnexoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.ItemLicitacaoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoDetalheDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoResumoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Modalidade;
import br.com.cernebr.gateway_nacional.licitacoes.dto.OrgaoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Portal;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Cliente da Bolsa de Licitações e Leilões (BLL).
 *
 * <p><b>Estratégia:</b> a BLL não publica API pública. A engine pública
 * {@code bll.org.br/processos} entrega HTML server-rendered com a listagem;
 * extraímos via Jsoup. Para detalhe, a página {@code /processos/{id}} traz
 * itens em uma tabela estável (id="itens-lote") — parsing limpo, sem
 * Selenium.</p>
 *
 * <p><b>Resiliência:</b> a BLL costuma ter páginas pesadas (~600KB cada) e
 * resposta lenta sob carga. O timeout do Jsoup é capped em 8s; o
 * CircuitBreaker trip após failure rate ≥50% protege a cascata sequencial
 * do {@code LicitacoesService}. Em caso de mudança de estrutura HTML, o
 * client devolve lista vazia (não 500) — degradação graciosa.</p>
 */
@Slf4j
@Component
public class BllComprasClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "BLL-Compras";

    private static final DateTimeFormatter BR_DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final String baseUrl;
    private final String userAgent;
    private final int timeoutMs;

    public BllComprasClient(@Value("${gateway.licitacoes.bll.base-url:https://bll.org.br}") String baseUrl,
                            @Value("${gateway.licitacoes.bll.user-agent:Mozilla/5.0 (compatible; GatewayNacional/1.0)}") String userAgent,
                            @Value("${gateway.licitacoes.bll.timeout-millis:8000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
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
        try {
            Document doc = Jsoup.connect(baseUrl + "/processos")
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .data("status", "publicado")
                    .data("uf", uf == null ? "" : uf.toUpperCase())
                    .get();

            List<LicitacaoResumoDTO> out = new ArrayList<>();
            for (Element row : doc.select("table.processos tbody tr, .processo-card")) {
                LicitacaoResumoDTO r = parseRow(row);
                if (r != null && matchesModalidade(r, modalidade)) {
                    out.add(r);
                }
            }
            return out;
        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BLL indisponível durante scraping da listagem: " + ex.getMessage(), ex);
        }
    }

    @Override
    @CircuitBreaker(name = "bllCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        try {
            Document doc = Jsoup.connect(baseUrl + "/processos/" + identificador)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .get();

            // BLL devolve 200 com placeholder "Processo não encontrado" em vez de 404.
            if (doc.text().toLowerCase().contains("processo não encontrado")) {
                return Optional.empty();
            }

            String numero = textOf(doc, ".processo-numero, h1.numero");
            String objeto = textOf(doc, ".processo-objeto, .objeto");
            String modalidade = textOf(doc, ".processo-modalidade, .modalidade");
            String orgaoNome = textOf(doc, ".processo-orgao, .orgao-nome");
            String uf = textOf(doc, ".processo-uf, .uf");
            String dataAbertura = textOf(doc, ".processo-abertura, .data-abertura");
            String dataEncerramento = textOf(doc, ".processo-encerramento, .data-encerramento");
            String valorEstimadoTxt = textOf(doc, ".processo-valor-estimado, .valor-estimado");

            List<ItemLicitacaoDTO> itens = new ArrayList<>();
            for (Element row : doc.select("table#itens-lote tbody tr, .item-lote")) {
                ItemLicitacaoDTO item = parseItem(row);
                if (item != null) itens.add(item);
            }

            List<AnexoDTO> anexos = new ArrayList<>();
            for (Element a : doc.select("a.processo-anexo, a[href$=.pdf]")) {
                String href = a.absUrl("href");
                if (!href.isBlank()) {
                    anexos.add(new AnexoDTO(a.text(), href, "application/pdf", null));
                }
            }

            return Optional.of(new LicitacaoDetalheDTO(
                    Portal.BLL,
                    identificador,
                    numero,
                    objeto,
                    Modalidade.infer(modalidade),
                    modalidade,
                    uf,
                    new OrgaoDTO(orgaoNome, null, null, identificador, null, uf),
                    parseDataHora(dataAbertura),
                    parseDataHora(dataEncerramento),
                    null,
                    parseValor(valorEstimadoTxt),
                    baseUrl + "/processos/" + identificador,
                    "PUBLICADO",
                    itens,
                    anexos
            ));
        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BLL indisponível durante scraping do detalhe: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) {
            throw ru;
        }
        log.warn("BLL listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BLL indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) {
            throw ru;
        }
        log.warn("BLL detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BLL indisponível ou Circuit Breaker aberto.", cause);
    }

    private LicitacaoResumoDTO parseRow(Element row) {
        String id = row.attr("data-id");
        if (id.isBlank()) {
            Element link = row.selectFirst("a[href*=/processos/]");
            if (link != null) {
                String href = link.attr("href");
                int idx = href.lastIndexOf('/');
                if (idx >= 0) id = href.substring(idx + 1);
            }
        }
        if (id.isBlank()) return null;

        String numero = textOf(row, "td.numero, .numero");
        String objeto = textOf(row, "td.objeto, .objeto");
        String modalidade = textOf(row, "td.modalidade, .modalidade");
        String orgao = textOf(row, "td.orgao, .orgao-nome");
        String uf = textOf(row, "td.uf, .uf");
        String dataAbertura = textOf(row, "td.abertura, .data-abertura");
        String dataEncerramento = textOf(row, "td.encerramento, .data-encerramento");
        String valor = textOf(row, "td.valor, .valor-estimado");

        return new LicitacaoResumoDTO(
                Portal.BLL,
                id,
                numero,
                truncate(objeto),
                Modalidade.infer(modalidade),
                uf,
                new OrgaoDTO(orgao, null, null, id, null, uf),
                parseDataHora(dataAbertura),
                parseDataHora(dataEncerramento),
                parseValor(valor),
                baseUrl + "/processos/" + id
        );
    }

    private ItemLicitacaoDTO parseItem(Element row) {
        String num = textOf(row, "td.numero, .item-numero");
        String desc = textOf(row, "td.descricao, .item-descricao");
        String qtd = textOf(row, "td.quantidade, .item-quantidade");
        String und = textOf(row, "td.unidade, .item-unidade");
        String vunit = textOf(row, "td.valor-unitario, .item-valor-unitario");
        String vtot = textOf(row, "td.valor-total, .item-valor-total");

        Integer numero = parseInt(num);
        if (numero == null && (desc == null || desc.isBlank())) return null;

        return new ItemLicitacaoDTO(
                numero,
                desc,
                parseDecimal(qtd),
                und,
                null,
                parseValor(vunit),
                parseValor(vtot),
                null
        );
    }

    /* helpers */

    private String textOf(Element el, String selector) {
        Element f = el.selectFirst(selector);
        return f != null ? f.text().trim() : null;
    }

    private boolean matchesModalidade(LicitacaoResumoDTO r, String filtro) {
        if (filtro == null || filtro.isBlank()) return true;
        return r.modalidade() != null && r.modalidade().slug().equalsIgnoreCase(filtro);
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 280 ? s.substring(0, 277) + "..." : s;
    }

    private OffsetDateTime parseDataHora(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // BR_DATETIME primeiro (dd/MM/yyyy HH:mm), depois fallback só data.
            LocalDateTime dt = LocalDateTime.parse(raw, BR_DATETIME);
            return dt.atOffset(ZoneOffset.of("-03:00")).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            try {
                LocalDate d = LocalDate.parse(raw, BR_DATE);
                return d.atStartOfDay(ZoneOffset.of("-03:00")).withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private BigDecimal parseValor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String n = raw.replaceAll("[^0-9,.-]", "").replace(".", "").replace(",", ".");
        try { return new BigDecimal(n); } catch (Exception ex) { return null; }
    }

    private BigDecimal parseDecimal(String raw) {
        return parseValor(raw);
    }

    private Integer parseInt(String raw) {
        if (raw == null) return null;
        String n = raw.replaceAll("[^0-9]", "");
        if (n.isBlank()) return null;
        try { return Integer.parseInt(n); } catch (Exception ex) { return null; }
    }
}
