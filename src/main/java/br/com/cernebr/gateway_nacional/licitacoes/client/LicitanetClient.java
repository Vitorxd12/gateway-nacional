package br.com.cernebr.gateway_nacional.licitacoes.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.licitacoes.dto.AnexoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.ItemLicitacaoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoDetalheDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoResumoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Modalidade;
import br.com.cernebr.gateway_nacional.licitacoes.dto.OrgaoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Portal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente do Licitanet.
 *
 * <p><b>Estratégia (auditada 2026-05):</b> a API REST interna
 * {@code /api/v2/processo} hoje exige um header "browser fingerprint"
 * dinâmico gerado pelo bundle JS — sem ele o portal devolve 400 com
 * {@code "Request is missing required browser fingerprint"}. Forjar esse
 * header é frágil e exigiria Chromium headless.</p>
 *
 * <p>O caminho oficial usado pelo próprio site é <i>SSR via Inertia.js</i>:
 * a página HTML em {@code /sessao-publica?page=N} já vem hidratada com o
 * JSON completo das publicações no atributo {@code data-page} do
 * {@code <div id="app">}. Esse JSON é exatamente o que o Vue consome
 * client-side. Lemos o HTML, extraímos o JSON, e o consumimos como se
 * fosse uma API — sem reescrita do contrato downstream.</p>
 *
 * <p><b>Por que isso é estável:</b> Inertia.js é parte do framework
 * (Laravel + Vue) — alterá-lo quebra a própria SPA. O CSRF token e o
 * fingerprint só são exigidos no caminho XHR; o SSR é público porque é
 * o que motores de busca indexam.</p>
 *
 * <p><b>Detalhe:</b> rotas {@code /sessao-publica/{id}} retornam 403 atrás
 * do Cloudflare (provavelmente só renderizam após login). Como a listagem
 * já entrega o payload completo de cada processo (incluindo
 * {@code notices}, {@code files}, {@code requestsClarification}),
 * resolvemos o detalhe varrendo as primeiras páginas até o ID bater —
 * cobre o caso de uso de "abri da listagem, quero ver o detalhe imediato".
 * O RAC do service cacheia o resultado, então a busca custosa só ocorre
 * uma vez por processo.</p>
 */
@Slf4j
@Component
public class LicitanetClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "Licitanet";

    private static final Pattern INERTIA_DATA_PAGE = Pattern.compile("data-page=\"([^\"]*)\"");
    private static final DateTimeFormatter BR_DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Quantas páginas (10 itens cada) varrer no caminho de detalhe antes de desistir. */
    private static final int MAX_PAGES_DETALHE = 20;
    /** Quantas páginas (10 itens cada) acumular numa listagem agregada. */
    private static final int MAX_PAGES_LISTAGEM = 5;
    private static final ParameterizedTypeReference<String> STRING_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;

    public LicitanetClient(RestClient.Builder builder,
                           @Value("${gateway.licitacoes.licitanet.api-base-url:https://www.licitanet.com.br}") String apiBaseUrl,
                           @Value("${gateway.licitacoes.licitanet.public-base-url:https://www.licitanet.com.br}") String publicBaseUrl) {
        // Headers replicam um Chrome real — sem Accept-Encoding compatível o
        // Cloudflare devolve 403 (medida anti-bot reativa). O RestClient lida
        // com gzip/br automaticamente quando o Accept-Encoding é informado.
        this.restClient = builder.baseUrl(apiBaseUrl)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .defaultHeader("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .defaultHeader("Accept-Encoding", "gzip, deflate")
                .build();
        // Spring Boot 4 autowire é Jackson v3 (tools.jackson.core); o restante
        // dos clients ainda usa as anotações Jackson v2. Instanciamos um
        // mapper v2 local para não conflitar com o ApplicationContext.
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public Portal portal() {
        return Portal.LICITANET;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "licitanetCB", fallbackMethod = "fallbackListar")
    public List<LicitacaoResumoDTO> listarAtivas(String uf, String modalidade) {
        List<LicitacaoResumoDTO> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES_LISTAGEM; page++) {
            LicitanetPage parsed = fetchPage(page);
            if (parsed == null || parsed.publications == null || parsed.publications.data == null) break;
            for (LicitanetProcesso p : parsed.publications.data) {
                LicitacaoResumoDTO r = toResumo(p);
                if (matchesUf(r, uf) && matchesModalidade(r, modalidade)) {
                    out.add(r);
                }
            }
            int total = parsed.publications.meta != null && parsed.publications.meta.totalPages != null
                    ? parsed.publications.meta.totalPages : 1;
            if (page >= total) break;
        }
        return out;
    }

    @Override
    @CircuitBreaker(name = "licitanetCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        // Detalhe individual exige login (rota /sessao-publica/{id} bate em
        // 403 Cloudflare). A listagem agregada já traz o payload completo
        // por processo — varremos páginas até encontrar o ID.
        Integer alvo;
        try {
            alvo = Integer.parseInt(identificador);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }

        for (int page = 1; page <= MAX_PAGES_DETALHE; page++) {
            LicitanetPage parsed = fetchPage(page);
            if (parsed == null || parsed.publications == null || parsed.publications.data == null) break;
            for (LicitanetProcesso p : parsed.publications.data) {
                if (alvo.equals(p.identifier)) {
                    return Optional.of(toDetalhe(p));
                }
            }
            int total = parsed.publications.meta != null && parsed.publications.meta.totalPages != null
                    ? parsed.publications.meta.totalPages : 1;
            if (page >= total) break;
        }
        return Optional.empty();
    }

    private LicitanetPage fetchPage(int page) {
        try {
            String html = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/sessao-publica").queryParam("page", page).build())
                    .accept(MediaType.TEXT_HTML)
                    .retrieve()
                    .body(STRING_TYPE);

            if (html == null || html.isEmpty()) {
                return null;
            }

            Matcher m = INERTIA_DATA_PAGE.matcher(html);
            if (!m.find()) {
                // Se o portal mudar o framework, devolvemos null e o caller
                // simplesmente termina a iteração — não jogamos exceção para
                // não derrubar o CB com base em uma página malformada.
                log.warn("[Licitanet] Atributo Inertia data-page não localizado na página {} (estrutura do portal pode ter mudado).", page);
                return null;
            }
            String jsonRaw = unescapeHtml(m.group(1));
            return objectMapper.readValue(jsonRaw, LicitanetPage.class);
        } catch (HttpClientErrorException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Licitanet retornou HTTP " + ex.getStatusCode().value() + " ao listar processos.", ex);
        } catch (RestClientException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Licitanet indisponível ao listar processos: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao interpretar resposta SSR do Licitanet: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("Licitanet listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Licitanet indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("Licitanet detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Licitanet indisponível ou Circuit Breaker aberto.", cause);
    }

    private boolean matchesUf(LicitacaoResumoDTO r, String filtro) {
        if (filtro == null || filtro.isBlank()) return true;
        return r.uf() != null && r.uf().equalsIgnoreCase(filtro);
    }

    private boolean matchesModalidade(LicitacaoResumoDTO r, String filtro) {
        if (filtro == null || filtro.isBlank()) return true;
        return r.modalidade() != null && r.modalidade().slug().equalsIgnoreCase(filtro);
    }

    private LicitacaoResumoDTO toResumo(LicitanetProcesso p) {
        String objeto = p.description;
        if (objeto != null && objeto.length() > 280) objeto = objeto.substring(0, 277) + "...";
        return new LicitacaoResumoDTO(
                Portal.LICITANET,
                String.valueOf(p.identifier),
                buildNumero(p),
                objeto,
                inferModalidade(p),
                p.uf,
                toOrgao(p),
                parseDataHora(p.datStartSession),
                parseDataHora(p.datFinishSession),
                null, // Licitanet não publica valor estimado na listagem.
                publicBaseUrl + "/sessao-publica/" + p.identifier
        );
    }

    private LicitacaoDetalheDTO toDetalhe(LicitanetProcesso p) {
        List<AnexoDTO> anexos = new ArrayList<>();
        if (p.notices != null) {
            for (LicitanetArquivo a : p.notices) {
                if (a == null) continue;
                anexos.add(new AnexoDTO(a.name, a.link, "application/octet-stream", null));
            }
        }
        if (p.files != null) {
            for (LicitanetArquivo a : p.files) {
                if (a == null) continue;
                anexos.add(new AnexoDTO(a.name, a.link, "application/octet-stream", null));
            }
        }

        // Listagem do Licitanet não inclui itens granulares — só metadados de
        // lote (numBatches). Devolvemos lista vazia em vez de "fingir" um
        // item: o cliente final que precisar de detalhamento por lote
        // precisa cadastrar-se no portal e acessar autenticado.
        List<ItemLicitacaoDTO> itens = List.of();

        return new LicitacaoDetalheDTO(
                Portal.LICITANET,
                String.valueOf(p.identifier),
                buildNumero(p),
                p.description,
                inferModalidade(p),
                p.disputeModeText != null ? p.disputeModeText : p.typeBidText,
                p.uf,
                toOrgao(p),
                parseDataHora(p.datStartSession),
                parseDataHora(p.datFinishSession),
                parseDataHora(p.datPublication),
                null,
                publicBaseUrl + "/sessao-publica/" + p.identifier,
                p.status,
                itens,
                anexos
        );
    }

    private OrgaoDTO toOrgao(LicitanetProcesso p) {
        if (p.buyer == null && p.document == null) return null;
        return new OrgaoDTO(
                p.buyer != null ? p.buyer.trim() : null,
                null,
                p.document,
                String.valueOf(p.identifier),
                p.city,
                p.uf
        );
    }

    private String buildNumero(LicitanetProcesso p) {
        if (p.biddingProcess != null && !p.biddingProcess.isBlank()) return p.biddingProcess;
        if (p.number != null && p.year != null) return p.number + "/" + p.year;
        if (p.number != null) return p.number;
        return "PROC-" + p.identifier;
    }

    private Modalidade inferModalidade(LicitanetProcesso p) {
        // disputeModeText ("CONCORRÊNCIA PÚBLICA", "PREGÃO ELETRÔNICO") é o
        // campo certo — typeBidText é o critério ("Menor Preço Global").
        return Modalidade.infer(p.disputeModeText != null ? p.disputeModeText : p.typeBidText);
    }

    private OffsetDateTime parseDataHora(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Listagem usa dd/MM/yyyy HH:mm:ss; metadados de upload (notices.datUpload)
        // usam yyyy-MM-dd HH:mm:ss. Toleramos os 2 e fazemos fallback para data pura.
        try {
            LocalDateTime dt = LocalDateTime.parse(raw, BR_DATETIME);
            return dt.atOffset(ZoneOffset.of("-03:00")).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            try {
                LocalDateTime dt = LocalDateTime.parse(raw, ISO_LOCAL);
                return dt.atOffset(ZoneOffset.of("-03:00")).withOffsetSameInstant(ZoneOffset.UTC);
            } catch (Exception ignored2) {
                try {
                    LocalDate d = LocalDate.parse(raw, BR_DATE);
                    return d.atStartOfDay(ZoneOffset.of("-03:00")).withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
                } catch (Exception ignored3) {
                    return null;
                }
            }
        }
    }

    private String unescapeHtml(String raw) {
        // O Inertia escapa apenas &quot; &amp; &lt; &gt; &#39; — substitutos
        // suficientes para o JSON SSR; ficar com java.net.URLDecoder ou Jsoup
        // aqui seria over-engineering para o conjunto fechado de entidades.
        return raw.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /* --------------------------- Payloads Inertia / Licitanet --------------------------- */

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LicitanetPage {
        @JsonProperty("props") public LicitanetProps props;
        public LicitanetPublications publications;

        @JsonProperty("props")
        public void setProps(LicitanetProps props) {
            this.props = props;
            if (props != null) {
                this.publications = props.publications;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LicitanetProps {
        public LicitanetPublications publications;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LicitanetPublications {
        public List<LicitanetProcesso> data;
        public LicitanetMeta meta;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LicitanetMeta {
        public Integer count;
        public Integer currentPage;
        public Integer perPage;
        public Integer total;
        public Integer totalPages;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LicitanetProcesso {
        public Integer identifier;
        public String status;
        public String description;
        public String datStartSession;
        public String datFinishSession;
        public String datPublication;
        public String document;       // CNPJ órgão
        public String buyer;          // razão social
        public String city;
        public String uf;
        public Integer numBatches;
        public Integer year;
        public String number;
        public String biddingProcess; // ex: "PRE002/2026"
        public Boolean isSuspended;
        public Boolean isCanceled;
        public Boolean isRevoked;
        public String typeBidText;
        public String typeModelText;
        public String disputeModeText;
        public BigDecimal valorEstimado; // raramente preenchido aqui
        public List<LicitanetArquivo> notices;
        public List<LicitanetArquivo> files;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LicitanetArquivo {
        public Integer identifier;
        public String datUpload;
        public String name;
        public String link;
    }
}
