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
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Cliente da Bolsa Nacional de Compras (BNC).
 *
 * <p><b>Estratégia:</b> a BNC operacionaliza o painel
 * {@code www.bnc.org.br/painel} através de chamadas AJAX para
 * {@code /api/painel/processos} (JSON puro). Interceptando essas chamadas
 * obtemos um contrato mais estável que o HTML. Mantém-se o cuidado de
 * tolerar variações de campo via {@link JsonIgnoreProperties}.</p>
 *
 * <p><b>Particularidades:</b> a BNC publica em fuso America/Sao_Paulo sem
 * offset; promovemos para UTC. Valores monetários vêm como decimais
 * tipados — não há sanitização de string a fazer. Filtro por UF é
 * server-side; modalidade é aplicado em memória (a BNC mistura "PE",
 * "Pregão Eletrônico" e "Pregão" para a mesma modalidade canônica).</p>
 */
@Slf4j
@Component
public class BncClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "BNC";

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ParameterizedTypeReference<BncResultado> RESULTADO_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<BncProcesso> PROCESSO_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String baseUrl;

    public BncClient(RestClient.Builder builder,
                     @Value("${gateway.licitacoes.bnc.base-url:https://www.bnc.org.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.baseUrl = baseUrl;
    }

    @Override
    public Portal portal() {
        return Portal.BNC;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "bncCB", fallbackMethod = "fallbackListar")
    public List<LicitacaoResumoDTO> listarAtivas(String uf, String modalidade) {
        try {
            BncResultado res = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/api/painel/processos")
                                .queryParam("status", "publicado")
                                .queryParam("pagina", 1)
                                .queryParam("tamanho", 50);
                        if (uf != null && !uf.isBlank()) {
                            b.queryParam("uf", uf.toUpperCase());
                        }
                        return b.build();
                    })
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(RESULTADO_TYPE);

            if (res == null || res.processos == null) {
                return Collections.emptyList();
            }
            return res.processos.stream()
                    .map(this::toResumo)
                    .filter(r -> matchesModalidade(r, modalidade))
                    .toList();
        } catch (HttpClientErrorException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BNC retornou HTTP " + ex.getStatusCode().value() + " ao listar processos.", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "bncCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        try {
            BncProcesso p = restClient.get()
                    .uri("/api/painel/processos/{id}", identificador)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(PROCESSO_TYPE);

            if (p == null) return Optional.empty();
            return Optional.of(toDetalhe(p));
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BNC retornou HTTP " + ex.getStatusCode().value() + " ao buscar detalhe.", ex);
        }
    }

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("BNC listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BNC indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) throw ru;
        log.warn("BNC detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BNC indisponível ou Circuit Breaker aberto.", cause);
    }

    private boolean matchesModalidade(LicitacaoResumoDTO r, String filtro) {
        if (filtro == null || filtro.isBlank()) return true;
        return r.modalidade() != null && r.modalidade().slug().equalsIgnoreCase(filtro);
    }

    private LicitacaoResumoDTO toResumo(BncProcesso p) {
        String obj = p.objeto;
        if (obj != null && obj.length() > 280) obj = obj.substring(0, 277) + "...";
        return new LicitacaoResumoDTO(
                Portal.BNC,
                p.id,
                p.numero,
                obj,
                Modalidade.infer(p.modalidade),
                p.uf,
                new OrgaoDTO(p.orgaoNome, null, p.orgaoCnpj, p.id, p.municipio, p.uf),
                parse(p.dataAbertura),
                parse(p.dataEncerramento),
                p.valorEstimado,
                baseUrl + "/processo/" + p.id
        );
    }

    private LicitacaoDetalheDTO toDetalhe(BncProcesso p) {
        List<ItemLicitacaoDTO> itens = p.itens == null ? List.of() :
                p.itens.stream()
                        .map(i -> new ItemLicitacaoDTO(
                                i.numero,
                                i.descricao,
                                i.quantidade,
                                i.unidade,
                                i.codigoCatalogo,
                                i.valorUnitario,
                                i.valorTotal,
                                i.exclusivoMeEpp))
                        .toList();

        List<AnexoDTO> anexos = p.anexos == null ? List.of() :
                p.anexos.stream()
                        .map(a -> new AnexoDTO(a.titulo, a.url, a.contentType, a.tamanho))
                        .toList();

        return new LicitacaoDetalheDTO(
                Portal.BNC,
                p.id,
                p.numero,
                p.objeto,
                Modalidade.infer(p.modalidade),
                p.modalidade,
                p.uf,
                new OrgaoDTO(p.orgaoNome, null, p.orgaoCnpj, p.id, p.municipio, p.uf),
                parse(p.dataAbertura),
                parse(p.dataEncerramento),
                parse(p.dataPublicacao),
                p.valorEstimado,
                baseUrl + "/processo/" + p.id,
                p.situacao,
                itens,
                anexos
        );
    }

    private OffsetDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            LocalDateTime local = LocalDateTime.parse(raw, ISO_LOCAL);
            return local.atOffset(ZoneOffset.of("-03:00")).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            return null;
        }
    }

    /* --------------------------- Payloads BNC --------------------------- */

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BncResultado(
            List<BncProcesso> processos,
            @JsonProperty("totalRegistros") Integer totalRegistros
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class BncProcesso {
        public String id;
        public String numero;
        public String objeto;
        public String modalidade;
        public String uf;
        public String municipio;
        @JsonProperty("orgao") public String orgaoNome;
        @JsonProperty("cnpjOrgao") public String orgaoCnpj;
        @JsonProperty("abertura") public String dataAbertura;
        @JsonProperty("encerramento") public String dataEncerramento;
        @JsonProperty("publicacao") public String dataPublicacao;
        @JsonProperty("valorEstimado") public BigDecimal valorEstimado;
        public String situacao;
        public List<BncItem> itens;
        public List<BncAnexo> anexos;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BncItem(
            Integer numero,
            String descricao,
            BigDecimal quantidade,
            String unidade,
            @JsonProperty("codigoCatalogo") String codigoCatalogo,
            @JsonProperty("valorUnitario") BigDecimal valorUnitario,
            @JsonProperty("valorTotal") BigDecimal valorTotal,
            @JsonProperty("exclusivoMeEpp") Boolean exclusivoMeEpp
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BncAnexo(
            String titulo,
            String url,
            @JsonProperty("contentType") String contentType,
            Long tamanho
    ) {}
}
