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
 * Cliente do Licitanet.
 *
 * <p><b>Estratégia:</b> a SPA pública {@code app.licitanet.com.br} consome
 * uma API REST interna em {@code api.licitanet.com.br/v2/processo} cujo
 * payload é razoavelmente estável. Não exige autenticação para consulta
 * pública de processos publicados.</p>
 *
 * <p><b>Particularidades:</b></p>
 * <ul>
 *   <li>Datas vêm em ISO-8601 com offset; o parsing usa
 *       {@link OffsetDateTime#parse} direto (sem suposição de fuso).</li>
 *   <li>O Licitanet costuma falhar com 504 sob carga de fim de mês — o CB
 *       e o timeout de 10s evitam que o gateway segure o cliente.</li>
 *   <li>O filtro por modalidade é nativo, mas usa rótulos próprios
 *       ({@code PE} = pregão eletrônico); o normalizador
 *       {@link Modalidade#infer} já cobre isso.</li>
 * </ul>
 */
@Slf4j
@Component
public class LicitanetClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "Licitanet";

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ParameterizedTypeReference<LicitanetResultado> RESULTADO_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<LicitanetProcesso> PROCESSO_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final String publicBaseUrl;

    public LicitanetClient(RestClient.Builder builder,
                           @Value("${gateway.licitacoes.licitanet.api-base-url:https://api.licitanet.com.br}") String apiBaseUrl,
                           @Value("${gateway.licitacoes.licitanet.public-base-url:https://app.licitanet.com.br}") String publicBaseUrl) {
        this.restClient = builder.baseUrl(apiBaseUrl).build();
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
        try {
            LicitanetResultado res = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/v2/processo")
                                .queryParam("status", "PUBLICADO")
                                .queryParam("page", 1)
                                .queryParam("limit", 50);
                        if (uf != null && !uf.isBlank()) {
                            b.queryParam("uf", uf.toUpperCase());
                        }
                        if (modalidade != null && !modalidade.isBlank()) {
                            b.queryParam("modalidade", modalidade);
                        }
                        return b.build();
                    })
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(RESULTADO_TYPE);

            if (res == null || res.data == null) return Collections.emptyList();

            return res.data.stream()
                    .map(this::toResumo)
                    // Re-aplicar filtro de modalidade caso o portal devolva
                    // rótulos diferentes do que pedimos (acontece em PE-SRP).
                    .filter(r -> matchesModalidade(r, modalidade))
                    .toList();
        } catch (HttpClientErrorException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Licitanet retornou HTTP " + ex.getStatusCode().value() + " ao listar processos.", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "licitanetCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        try {
            LicitanetProcesso p = restClient.get()
                    .uri("/v2/processo/{id}", identificador)
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
                    "Licitanet retornou HTTP " + ex.getStatusCode().value() + " ao buscar detalhe.", ex);
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

    private boolean matchesModalidade(LicitacaoResumoDTO r, String filtro) {
        if (filtro == null || filtro.isBlank()) return true;
        return r.modalidade() != null && r.modalidade().slug().equalsIgnoreCase(filtro);
    }

    private LicitacaoResumoDTO toResumo(LicitanetProcesso p) {
        String obj = p.objeto;
        if (obj != null && obj.length() > 280) obj = obj.substring(0, 277) + "...";
        return new LicitacaoResumoDTO(
                Portal.LICITANET,
                p.id,
                p.numero,
                obj,
                Modalidade.infer(p.modalidade),
                p.uf,
                new OrgaoDTO(p.orgao, null, p.orgaoCnpj, p.id, p.municipio, p.uf),
                parse(p.dataAbertura),
                parse(p.dataEncerramento),
                p.valorEstimado,
                publicBaseUrl + "/processo/" + p.id
        );
    }

    private LicitacaoDetalheDTO toDetalhe(LicitanetProcesso p) {
        List<ItemLicitacaoDTO> itens = p.itens == null ? List.of() :
                p.itens.stream().map(i -> new ItemLicitacaoDTO(
                        i.numero, i.descricao, i.quantidade, i.unidade, i.codigoCatalogo,
                        i.valorUnitario, i.valorTotal, i.meEpp
                )).toList();

        List<AnexoDTO> anexos = p.anexos == null ? List.of() :
                p.anexos.stream().map(a -> new AnexoDTO(a.nome, a.url, a.contentType, a.tamanho)).toList();

        return new LicitacaoDetalheDTO(
                Portal.LICITANET,
                p.id,
                p.numero,
                p.objeto,
                Modalidade.infer(p.modalidade),
                p.modalidade,
                p.uf,
                new OrgaoDTO(p.orgao, null, p.orgaoCnpj, p.id, p.municipio, p.uf),
                parse(p.dataAbertura),
                parse(p.dataEncerramento),
                parse(p.dataPublicacao),
                p.valorEstimado,
                publicBaseUrl + "/processo/" + p.id,
                p.situacao,
                itens,
                anexos
        );
    }

    private OffsetDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Licitanet publica em ISO-8601 com offset; mas eventualmente vem sem
        // (campos derivados). Tentamos OffsetDateTime e caímos em LocalDateTime
        // assumindo -03:00 para o BR — promovemos para UTC em qualquer caso.
        try {
            return OffsetDateTime.parse(raw).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(raw, ISO_LOCAL)
                        .atOffset(ZoneOffset.of("-03:00"))
                        .withOffsetSameInstant(ZoneOffset.UTC);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /* --------------------------- Payloads Licitanet --------------------------- */

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LicitanetResultado(List<LicitanetProcesso> data, @JsonProperty("total") Integer total) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LicitanetProcesso {
        public String id;
        public String numero;
        public String objeto;
        public String modalidade;
        public String uf;
        public String municipio;
        public String orgao;
        @JsonProperty("orgaoCnpj") public String orgaoCnpj;
        @JsonProperty("dataAbertura") public String dataAbertura;
        @JsonProperty("dataEncerramento") public String dataEncerramento;
        @JsonProperty("dataPublicacao") public String dataPublicacao;
        @JsonProperty("valorEstimado") public BigDecimal valorEstimado;
        public String situacao;
        public List<LicitanetItem> itens;
        public List<LicitanetAnexo> anexos;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LicitanetItem(
            Integer numero, String descricao, BigDecimal quantidade, String unidade,
            @JsonProperty("codigoCatalogo") String codigoCatalogo,
            @JsonProperty("valorUnitario") BigDecimal valorUnitario,
            @JsonProperty("valorTotal") BigDecimal valorTotal,
            @JsonProperty("meEpp") Boolean meEpp
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LicitanetAnexo(
            String nome, String url,
            @JsonProperty("contentType") String contentType,
            Long tamanho
    ) {}
}
