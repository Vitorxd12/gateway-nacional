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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Cliente do Portal Nacional de Compras Públicas (PNCP — sucessor oficial do
 * ComprasNet operado pelo SERPRO).
 *
 * <p><b>Por que PNCP e não comprasnet.gov.br direto:</b> a Lei 14.133/2021
 * obriga toda contratação pública a ser publicada no PNCP. Ele já agrega o
 * ComprasNet federal, BBMNet, BLL e portais estaduais que adotaram a
 * integração. O endpoint REST {@code /api/consulta/v1/contratacoes/proposta}
 * é a fonte canônica e exige zero credencial — perfeito para o gateway.</p>
 *
 * <p><b>Identificador composto:</b> o PNCP usa
 * {@code {cnpjOrgao}/{ano}/{sequencial}}. Para manter o slug uma única
 * string nas rotas REST, juntamos com {@code -} no DTO e desmembramos no
 * detalhe ({@link #buscarDetalhe}).</p>
 */
@Slf4j
@Component
public class ComprasNetClient implements LicitacaoClient {

    public static final String PROVIDER_NAME = "PNCP-ComprasNet";

    private static final DateTimeFormatter PNCP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter PNCP_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ParameterizedTypeReference<PncpResultado> RESULTADO_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<PncpContratacao> CONTRATACAO_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<PncpItem>> ITENS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public ComprasNetClient(RestClient.Builder builder,
                            @Value("${gateway.licitacoes.comprasnet.base-url:https://pncp.gov.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Portal portal() {
        return Portal.COMPRASNET;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "comprasnetCB", fallbackMethod = "fallbackListar")
    public List<LicitacaoResumoDTO> listarAtivas(String uf, String modalidade) {
        LocalDate hoje = LocalDate.now(ZoneOffset.of("-03:00"));
        String dataInicial = hoje.format(PNCP_DATE);
        String dataFinal = hoje.plusDays(90).format(PNCP_DATE);

        try {
            PncpResultado res = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/api/consulta/v1/contratacoes/proposta")
                                .queryParam("dataFinal", dataFinal)
                                .queryParam("dataInicial", dataInicial)
                                .queryParam("pagina", 1)
                                .queryParam("tamanhoPagina", 50);
                        if (uf != null && !uf.isBlank()) {
                            b.queryParam("uf", uf.toUpperCase());
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(RESULTADO_TYPE);

            if (res == null || res.data == null) {
                return Collections.emptyList();
            }

            return res.data.stream()
                    .map(this::toResumo)
                    .filter(r -> matchesModalidade(r, modalidade))
                    .toList();
        } catch (HttpClientErrorException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "PNCP retornou HTTP " + ex.getStatusCode().value() + " ao listar contratações.", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "comprasnetCB", fallbackMethod = "fallbackDetalhe")
    public Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador) {
        // PNCP usa cnpj/ano/sequencial — no slug colamos com '-' para sobreviver à URL.
        String[] partes = identificador.split("-");
        if (partes.length < 3) {
            return Optional.empty();
        }
        String cnpj = partes[0];
        String ano = partes[1];
        String sequencial = partes[2];

        try {
            PncpContratacao contratacao = restClient.get()
                    .uri("/api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{seq}", cnpj, ano, sequencial)
                    .retrieve()
                    .body(CONTRATACAO_TYPE);

            if (contratacao == null) {
                return Optional.empty();
            }

            List<PncpItem> itens = Optional.ofNullable(
                    restClient.get()
                            .uri("/api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{seq}/itens", cnpj, ano, sequencial)
                            .retrieve()
                            .body(ITENS_TYPE)
            ).orElse(List.of());

            return Optional.of(toDetalhe(contratacao, itens));
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "PNCP retornou HTTP " + ex.getStatusCode().value() + " ao buscar detalhe.", ex);
        }
    }

    @SuppressWarnings("unused")
    private List<LicitacaoResumoDTO> fallbackListar(String uf, String modalidade, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) {
            throw ru;
        }
        log.warn("ComprasNet listar fallback uf={} causa={}", uf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "PNCP indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private Optional<LicitacaoDetalheDTO> fallbackDetalhe(String identificador, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) {
            throw ru;
        }
        log.warn("ComprasNet detalhe fallback id={} causa={}", identificador, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "PNCP indisponível ou Circuit Breaker aberto.", cause);
    }

    private boolean matchesModalidade(LicitacaoResumoDTO r, String filtro) {
        if (filtro == null || filtro.isBlank()) return true;
        return r.modalidade() != null && r.modalidade().slug().equalsIgnoreCase(filtro);
    }

    private LicitacaoResumoDTO toResumo(PncpContratacao c) {
        String identificador = c.cnpjOrgao() + "-" + c.anoCompra + "-" + c.sequencialCompra;
        String objeto = c.objetoCompra;
        if (objeto != null && objeto.length() > 280) {
            objeto = objeto.substring(0, 277) + "...";
        }
        return new LicitacaoResumoDTO(
                Portal.COMPRASNET,
                identificador,
                c.numeroCompra != null ? c.numeroCompra : ("Compra " + c.sequencialCompra + "/" + c.anoCompra),
                objeto,
                Modalidade.infer(c.modalidadeNome),
                c.unidadeOrgao != null ? c.unidadeOrgao.ufSigla : null,
                toOrgao(c),
                parse(c.dataAberturaProposta),
                parse(c.dataEncerramentoProposta),
                c.valorTotalEstimado != null ? new BigDecimal(c.valorTotalEstimado) : null,
                "https://pncp.gov.br/app/editais/" + c.cnpjOrgao() + "/" + c.anoCompra + "/" + c.sequencialCompra
        );
    }

    private LicitacaoDetalheDTO toDetalhe(PncpContratacao c, List<PncpItem> itens) {
        List<ItemLicitacaoDTO> itensDto = itens.stream()
                .map(i -> new ItemLicitacaoDTO(
                        i.numeroItem,
                        i.descricao,
                        i.quantidade != null ? new BigDecimal(i.quantidade) : null,
                        i.unidadeMedida,
                        i.codigoItem,
                        i.valorUnitarioEstimado != null ? new BigDecimal(i.valorUnitarioEstimado) : null,
                        i.valorTotal != null ? new BigDecimal(i.valorTotal) : null,
                        Boolean.TRUE.equals(i.beneficioMeEpp)
                ))
                .toList();

        return new LicitacaoDetalheDTO(
                Portal.COMPRASNET,
                c.cnpjOrgao() + "-" + c.anoCompra + "-" + c.sequencialCompra,
                c.numeroCompra != null ? c.numeroCompra : ("Compra " + c.sequencialCompra + "/" + c.anoCompra),
                c.objetoCompra,
                Modalidade.infer(c.modalidadeNome),
                c.modalidadeNome,
                c.unidadeOrgao != null ? c.unidadeOrgao.ufSigla : null,
                toOrgao(c),
                parse(c.dataAberturaProposta),
                parse(c.dataEncerramentoProposta),
                parse(c.dataPublicacaoPncp),
                c.valorTotalEstimado != null ? new BigDecimal(c.valorTotalEstimado) : null,
                "https://pncp.gov.br/app/editais/" + c.cnpjOrgao() + "/" + c.anoCompra + "/" + c.sequencialCompra,
                c.situacaoCompraNome,
                itensDto,
                // PNCP expõe anexos via endpoint separado /arquivos; devolvemos
                // a lista vazia aqui e instrumentamos como TODO — chamada extra
                // só vale a pena se o cliente final pedir os PDFs com frequência.
                List.<AnexoDTO>of()
        );
    }

    private OrgaoDTO toOrgao(PncpContratacao c) {
        if (c.orgaoEntidade == null && c.unidadeOrgao == null) return null;
        String nome = c.orgaoEntidade != null ? c.orgaoEntidade.razaoSocial : null;
        String municipio = c.unidadeOrgao != null ? c.unidadeOrgao.municipioNome : null;
        String uf = c.unidadeOrgao != null ? c.unidadeOrgao.ufSigla : null;
        return new OrgaoDTO(nome, c.unidadeOrgao != null ? c.unidadeOrgao.codigoUnidade : null, c.cnpjOrgao(), null, municipio, uf);
    }

    private OffsetDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // PNCP publica em America/Sao_Paulo sem offset; assumimos -03:00 e
            // promovemos para UTC para o consumidor não precisar conhecer o fuso.
            LocalDateTime local = LocalDateTime.parse(raw, PNCP_DATETIME);
            return local.atOffset(ZoneOffset.of("-03:00")).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            return null;
        }
    }

    /* --------------------------- Payloads PNCP --------------------------- */

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PncpResultado(
            List<PncpContratacao> data,
            @JsonProperty("totalPaginas") Integer totalPaginas,
            @JsonProperty("totalRegistros") Integer totalRegistros
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PncpContratacao {
        @JsonProperty("numeroControlePNCP") public String numeroControle;
        @JsonProperty("orgaoEntidade") public PncpOrgao orgaoEntidade;
        @JsonProperty("unidadeOrgao") public PncpUnidade unidadeOrgao;
        @JsonProperty("anoCompra") public Integer anoCompra;
        @JsonProperty("sequencialCompra") public Integer sequencialCompra;
        @JsonProperty("numeroCompra") public String numeroCompra;
        @JsonProperty("modalidadeNome") public String modalidadeNome;
        @JsonProperty("objetoCompra") public String objetoCompra;
        @JsonProperty("valorTotalEstimado") public String valorTotalEstimado;
        @JsonProperty("dataAberturaProposta") public String dataAberturaProposta;
        @JsonProperty("dataEncerramentoProposta") public String dataEncerramentoProposta;
        @JsonProperty("dataPublicacaoPncp") public String dataPublicacaoPncp;
        @JsonProperty("situacaoCompraNome") public String situacaoCompraNome;
        // O CNPJ vem dentro de orgaoEntidade na listagem; derivamos no toResumo
        public String cnpjOrgao() {
            return orgaoEntidade != null ? orgaoEntidade.cnpj : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PncpOrgao(String cnpj, @JsonProperty("razaoSocial") String razaoSocial) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PncpUnidade(
            @JsonProperty("codigoUnidade") String codigoUnidade,
            @JsonProperty("ufSigla") String ufSigla,
            @JsonProperty("municipioNome") String municipioNome
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PncpItem(
            @JsonProperty("numeroItem") Integer numeroItem,
            @JsonProperty("descricao") String descricao,
            @JsonProperty("quantidade") String quantidade,
            @JsonProperty("unidadeMedida") String unidadeMedida,
            @JsonProperty("codigoItem") String codigoItem,
            @JsonProperty("valorUnitarioEstimado") String valorUnitarioEstimado,
            @JsonProperty("valorTotal") String valorTotal,
            @JsonProperty("beneficioMeEpp") Boolean beneficioMeEpp
    ) {}
}
