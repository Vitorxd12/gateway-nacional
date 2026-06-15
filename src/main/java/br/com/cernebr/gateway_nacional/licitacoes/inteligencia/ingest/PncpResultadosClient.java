package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Cliente da fase de RESULTADOS do PNCP — onde finalmente existem os CNPJs de
 * fornecedor que a federação de editais ativos não enxerga.
 *
 * <p><b>Dois passos:</b></p>
 * <ol>
 *   <li>{@link #listarContratacoesPorMunicipio} — varre
 *       {@code /api/consulta/v1/contratacoes/publicacao} filtrando por
 *       {@code codigoMunicipioIbge} numa janela passada (editais já encerrados,
 *       ao contrário do {@code ComprasNetClient} que só vê os ativos).</li>
 *   <li>{@link #buscarResultados} — para cada contratação, lê os itens e os
 *       resultados por item ({@code niFornecedor}, {@code ordemClassificacao},
 *       {@code valorTotalHomologado}).</li>
 * </ol>
 *
 * <p><b>⚠️ Endpoints a validar contra o PNCP em produção:</b> a API do PNCP
 * versiona/deprecia rotas sem aviso (o próprio {@code ComprasNetClient} anota
 * que {@code /itens} da consulta unificada passou a devolver 404). Por isso este
 * client é <em>fail-soft</em>: qualquer rota de resultados que falhe devolve
 * lista vazia e loga em debug, sem derrubar a ingestão. Confirme os caminhos na
 * primeira carga real (ver o seed de Aracaju).</p>
 *
 * <p>Conditional ao flag do módulo — não existe quando a Inteligência está off.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class PncpResultadosClient {

    private static final DateTimeFormatter PNCP_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ZoneOffset BR = ZoneOffset.of("-03:00");

    private static final ParameterizedTypeReference<PncpPage> PAGE_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<PncpItem>> ITENS_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<PncpResultado>> RESULTADOS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public PncpResultadosClient(RestClient.Builder builder,
                                @Value("${gateway.licitacoes.comprasnet.base-url:https://pncp.gov.br}") String baseUrl) {
        // PNCP /contratacoes/publicacao (janelas amplas) e /resultados são
        // consultas lentas — o timeout curto do RestClient global ("Request
        // cancelled" em ~5s) zerava a ingestão. Timeout generoso aqui; o
        // paralelismo por item (Virtual Threads) é que absorve a latência.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Uma página de contratações publicadas no município, para uma modalidade.
     * Datas no formato {@code yyyyMMdd}. Devolve lista vazia (nunca null) quando
     * o portal responde sem dados ou a página excede o total.
     */
    @CircuitBreaker(name = "pncpIntelCB", fallbackMethod = "fallbackContratacoes")
    public List<PncpContratacaoResumo> listarContratacoesPorMunicipio(
            String municipioIbge, String dataInicial, String dataFinal, int codigoModalidade, int pagina) {
        try {
            PncpPage page = restClient.get()
                    .uri(b -> b.path("/api/consulta/v1/contratacoes/publicacao")
                            .queryParam("dataInicial", dataInicial)
                            .queryParam("dataFinal", dataFinal)
                            .queryParam("codigoModalidadeContratacao", codigoModalidade)
                            .queryParam("codigoMunicipioIbge", municipioIbge)
                            .queryParam("pagina", pagina)
                            .queryParam("tamanhoPagina", 50)
                            .build())
                    .retrieve()
                    .body(PAGE_TYPE);

            if (page == null || page.data == null || page.data.isEmpty()) {
                return Collections.emptyList();
            }
            List<PncpContratacaoResumo> out = new ArrayList<>(page.data.size());
            for (PncpContratacao c : page.data) {
                out.add(toResumo(c));
            }
            return out;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND
                    || ex.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                // 422/404 = sem registros para o filtro (modalidade sem editais na
                // janela). Não é falha de portal — encerra a paginação.
                return Collections.emptyList();
            }
            throw ex; // 5xx etc. → deixa o CircuitBreaker contabilizar.
        }
    }

    /**
     * Resultados (fornecedores) de uma contratação. Lê os itens e busca os
     * resultados de cada item EM PARALELO com Virtual Threads (M4) — as chamadas
     * são I/O-bound, então o tempo cai do somatório para o pior item.
     *
     * <p><b>Skip:</b> itens em fase terminal sem resultado (anulado/cancelado/
     * deserto/fracassado) ou com {@code temResultado=false} não geram chamada
     * {@code /resultados} — poupa rede e tempo. Fail-soft por item.</p>
     */
    @CircuitBreaker(name = "pncpIntelCB", fallbackMethod = "fallbackResultados")
    public List<PncpResultadoFornecedor> buscarResultados(String cnpjOrgao, int ano, int sequencial) {
        List<PncpItem> itens = fetchItens(cnpjOrgao, ano, sequencial);
        if (itens.isEmpty()) {
            return Collections.emptyList();
        }
        List<PncpItem> alvo = itens.stream().filter(PncpResultadosClient::temResultadoPossivel).toList();
        if (alvo.isEmpty()) {
            return Collections.emptyList();
        }
        // Uma requisição /resultados por item, concorrente em virtual threads.
        // RestClient é imutável/thread-safe; a concorrência é por edital (a
        // ingestão segue espaçando editais pelo rate-limit).
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<PncpResultadoFornecedor>>> futures = alvo.stream()
                    .map(item -> exec.submit(() -> mapResultadosDoItem(cnpjOrgao, ano, sequencial, item.numeroItem)))
                    .toList();
            List<PncpResultadoFornecedor> out = new ArrayList<>();
            for (Future<List<PncpResultadoFornecedor>> f : futures) {
                try {
                    out.addAll(f.get());
                } catch (ExecutionException ex) {
                    log.debug("PNCP resultado item falhou {}-{}-{}: {}", cnpjOrgao, ano, sequencial, ex.toString());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return out;
        }
    }

    private List<PncpResultadoFornecedor> mapResultadosDoItem(String cnpjOrgao, int ano, int sequencial, int numeroItem) {
        List<PncpResultado> res = fetchResultadosItem(cnpjOrgao, ano, sequencial, numeroItem);
        if (res.isEmpty()) {
            return List.of();
        }
        List<PncpResultadoFornecedor> out = new ArrayList<>(res.size());
        for (PncpResultado r : res) {
            out.add(new PncpResultadoFornecedor(
                    r.niFornecedor,
                    r.nomeRazaoSocialFornecedor,
                    r.porteFornecedorNome,
                    // PNCP nomeia a classificação como ordemClassificacaoSrp;
                    // ordemClassificacao é fallback caso o portal mude.
                    r.ordemClassificacaoSrp != null ? r.ordemClassificacaoSrp : r.ordemClassificacao,
                    toDecimal(r.valorTotalHomologado),
                    toDecimal(r.valorUnitarioHomologado),
                    numeroItem,
                    r.situacaoCompraItemResultadoNome,
                    parseDate(r.dataResultado)));
        }
        return out;
    }

    /** Item pode ter resultado homologado? Pula fases terminais sem vencedor. */
    private static boolean temResultadoPossivel(PncpItem i) {
        if (i.numeroItem == null) {
            return false;
        }
        if (Boolean.FALSE.equals(i.temResultado)) {
            return false;
        }
        // 3=Anulado/Revogado/Cancelado, 4=Deserto, 5=Fracassado → sem resultado.
        Integer s = i.situacaoCompraItemId;
        return s == null || (s != 3 && s != 4 && s != 5);
    }

    private List<PncpItem> fetchItens(String cnpjOrgao, int ano, int sequencial) {
        try {
            List<PncpItem> itens = restClient.get()
                    .uri("/api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{seq}/itens", cnpjOrgao, ano, sequencial)
                    .retrieve()
                    .body(ITENS_TYPE);
            return itens != null ? itens : List.of();
        } catch (RuntimeException ex) {
            log.debug("PNCP /itens indisponível para {}-{}-{}: {}", cnpjOrgao, ano, sequencial, ex.toString());
            return List.of();
        }
    }

    private List<PncpResultado> fetchResultadosItem(String cnpjOrgao, int ano, int sequencial, int numeroItem) {
        try {
            List<PncpResultado> res = restClient.get()
                    .uri("/api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{seq}/itens/{item}/resultados",
                            cnpjOrgao, ano, sequencial, numeroItem)
                    .retrieve()
                    .body(RESULTADOS_TYPE);
            return res != null ? res : List.of();
        } catch (RuntimeException ex) {
            log.debug("PNCP /resultados indisponível para {}-{}-{} item {}: {}",
                    cnpjOrgao, ano, sequencial, numeroItem, ex.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unused")
    private List<PncpContratacaoResumo> fallbackContratacoes(
            String municipioIbge, String dataInicial, String dataFinal, int codigoModalidade, int pagina,
            Throwable cause) {
        log.warn("PNCP contratações fallback municipio={} modalidade={} causa={}",
                municipioIbge, codigoModalidade, cause.toString());
        return Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<PncpResultadoFornecedor> fallbackResultados(
            String cnpjOrgao, int ano, int sequencial, Throwable cause) {
        log.warn("PNCP resultados fallback {}-{}-{} causa={}", cnpjOrgao, ano, sequencial, cause.toString());
        return Collections.emptyList();
    }

    private PncpContratacaoResumo toResumo(PncpContratacao c) {
        String cnpj = c.orgaoEntidade != null ? c.orgaoEntidade.cnpj : null;
        String orgaoNome = c.orgaoEntidade != null ? c.orgaoEntidade.razaoSocial : null;
        String municipioNome = c.unidadeOrgao != null ? c.unidadeOrgao.municipioNome : null;
        String uf = c.unidadeOrgao != null ? c.unidadeOrgao.ufSigla : null;
        String municipioIbge = c.unidadeOrgao != null ? c.unidadeOrgao.codigoIbge : null;
        return new PncpContratacaoResumo(
                cnpj, c.anoCompra, c.sequencialCompra, c.numeroCompra, c.objetoCompra, c.modalidadeNome,
                toDecimal(c.valorTotalEstimado), toDecimal(c.valorTotalHomologado),
                parse(c.dataAberturaProposta), parse(c.dataEncerramentoProposta), parse(c.dataPublicacaoPncp),
                c.situacaoCompraNome, orgaoNome, uf, municipioNome, municipioIbge);
    }

    private static OffsetDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, PNCP_DATETIME).atOffset(BR).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Datas de resultado vêm como {@code yyyy-MM-dd} (sem hora). */
    private static OffsetDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(raw).atStartOfDay()
                    .atOffset(BR).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static BigDecimal toDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ── Records de transporte do JSON cru do PNCP ──────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PncpPage {
        @JsonProperty("data") public List<PncpContratacao> data;
        @JsonProperty("totalPaginas") public Integer totalPaginas;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PncpContratacao {
        @JsonProperty("orgaoEntidade") public PncpOrgao orgaoEntidade;
        @JsonProperty("unidadeOrgao") public PncpUnidade unidadeOrgao;
        @JsonProperty("anoCompra") public Integer anoCompra;
        @JsonProperty("sequencialCompra") public Integer sequencialCompra;
        @JsonProperty("numeroCompra") public String numeroCompra;
        @JsonProperty("modalidadeNome") public String modalidadeNome;
        @JsonProperty("objetoCompra") public String objetoCompra;
        @JsonProperty("valorTotalEstimado") public String valorTotalEstimado;
        @JsonProperty("valorTotalHomologado") public String valorTotalHomologado;
        @JsonProperty("dataAberturaProposta") public String dataAberturaProposta;
        @JsonProperty("dataEncerramentoProposta") public String dataEncerramentoProposta;
        @JsonProperty("dataPublicacaoPncp") public String dataPublicacaoPncp;
        @JsonProperty("situacaoCompraNome") public String situacaoCompraNome;
    }

    private record PncpOrgao(String cnpj, @JsonProperty("razaoSocial") String razaoSocial) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PncpUnidade {
        @JsonProperty("municipioNome") public String municipioNome;
        @JsonProperty("ufSigla") public String ufSigla;
        @JsonProperty("codigoIbge") public String codigoIbge;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PncpItem {
        @JsonProperty("numeroItem") public Integer numeroItem;
        @JsonProperty("situacaoCompraItemId") public Integer situacaoCompraItemId;
        @JsonProperty("temResultado") public Boolean temResultado;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PncpResultado {
        @JsonProperty("niFornecedor") public String niFornecedor;
        @JsonProperty("nomeRazaoSocialFornecedor") public String nomeRazaoSocialFornecedor;
        @JsonProperty("porteFornecedorNome") public String porteFornecedorNome;
        @JsonProperty("ordemClassificacao") public Integer ordemClassificacao;
        @JsonProperty("ordemClassificacaoSrp") public Integer ordemClassificacaoSrp;
        @JsonProperty("valorTotalHomologado") public String valorTotalHomologado;
        @JsonProperty("valorUnitarioHomologado") public String valorUnitarioHomologado;
        @JsonProperty("situacaoCompraItemResultadoNome") public String situacaoCompraItemResultadoNome;
        @JsonProperty("dataResultado") public String dataResultado;
    }
}
