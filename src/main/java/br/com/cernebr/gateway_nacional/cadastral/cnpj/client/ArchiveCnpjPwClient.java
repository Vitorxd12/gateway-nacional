package br.com.cernebr.gateway_nacional.cadastral.cnpj.client;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnaeDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.ContatosDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.EnderecoCompletoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.InformacoesSimplesMeiDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.MunicipioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.NaturezaJuridicaDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SituacaoCadastralDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SocioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TelefoneDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TipoEstabelecimento;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.capQsa;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.digitsOnly;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseCapitalCentavosInteiros;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseDate;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.safeTrim;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.splitTelefone;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.upperTrim;

/**
 * Provider de background / dump local — Archive CNPJ.PW
 * ({@code https://archive.cnpj.pw}).
 *
 * <p>Fornece fragmentos JSON do dump RFB com cadência horária. É a apólice de
 * seguro do gateway: quando todas as APIs REST online estão fora simultaneamente
 * (raro mas catastrófico), este client ainda devolve o último snapshot oficial.
 * Capital social vem como inteiro em centavos (formato Serpro), portanto a
 * divisão por 100 é mandatória.</p>
 *
 * <p>Postura defensiva: timeout curto, payload sob {@code @JsonIgnoreProperties},
 * e qualquer 404 vira falha transitória — o orquestrador apenas remove este
 * provider da lista de sobreviventes e segue com os demais.</p>
 */
@Slf4j
@Component
public class ArchiveCnpjPwClient implements CnpjConsolidadoClientProvider {

    public static final String PROVIDER_NAME = "ArchiveCnpjPw";

    private final RestClient restClient;
    private final String pathTemplate;

    public ArchiveCnpjPwClient(RestClient.Builder builder,
                               @Value("${gateway.cnpj.archive.base-url:https://archive.cnpj.pw}") String baseUrl,
                               @Value("${gateway.cnpj.archive.path:/cnpj/{cnpj}.json}") String pathTemplate) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.pathTemplate = pathTemplate;
    }

    @Override
    @CircuitBreaker(name = "archiveCnpjPwCB", fallbackMethod = "fallback")
    public CnpjConsolidadoDTO fetch(String cnpj) {
        ArchivePayload payload = restClient.get()
                .uri(pathTemplate, cnpj)
                .retrieve()
                .body(ArchivePayload.class);

        if (payload == null || payload.cnpj == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Archive CNPJ.PW retornou snapshot vazio ou CNPJ não localizado.");
        }
        return payload.toConsolidado();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjConsolidadoDTO fallback(String cnpj, Throwable cause) {
        log.warn("Archive CNPJ.PW fallback acionado cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Archive CNPJ.PW indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ArchivePayload(
            String cnpj,
            @JsonProperty("razao_social") String razaoSocial,
            @JsonProperty("nome_fantasia") String nomeFantasia,
            @JsonProperty("identificador_matriz_filial") Integer matrizFilial,
            @JsonProperty("situacao_cadastral") Integer situacaoCadastral,
            @JsonProperty("descricao_situacao_cadastral") String descricaoSituacao,
            @JsonProperty("data_situacao_cadastral") String dataSituacao,
            @JsonProperty("motivo_situacao_cadastral") String motivoSituacao,
            @JsonProperty("data_inicio_atividade") String dataAbertura,
            @JsonProperty("cnae_fiscal") String cnaeFiscal,
            @JsonProperty("cnae_fiscal_descricao") String cnaeFiscalDescricao,
            @JsonProperty("cnaes_secundarios") List<ArchiveCnae> cnaesSecundarios,
            @JsonProperty("natureza_juridica") String naturezaJuridica,
            @JsonProperty("descricao_natureza_juridica") String descricaoNaturezaJuridica,
            @JsonProperty("logradouro") String logradouro,
            @JsonProperty("numero") String numero,
            @JsonProperty("complemento") String complemento,
            @JsonProperty("bairro") String bairro,
            @JsonProperty("cep") String cep,
            @JsonProperty("uf") String uf,
            @JsonProperty("municipio") String municipio,
            @JsonProperty("codigo_municipio_ibge") String codigoMunicipioIbge,
            @JsonProperty("ddd_telefone_1") String dddTel1,
            @JsonProperty("ddd_telefone_2") String dddTel2,
            @JsonProperty("email") String email,
            @JsonProperty("capital_social") Object capitalSocial,
            @JsonProperty("porte") String porte,
            @JsonProperty("opcao_pelo_simples") Boolean opcaoSimples,
            @JsonProperty("opcao_pelo_mei") Boolean opcaoMei,
            @JsonProperty("qsa") List<ArchiveSocio> qsa
    ) {
        CnpjConsolidadoDTO toConsolidado() {
            SituacaoCadastralDTO sit = new SituacaoCadastralDTO(
                    situacaoCadastral != null ? String.format("%02d", situacaoCadastral) : null,
                    upperTrim(descricaoSituacao),
                    parseDate(dataSituacao),
                    safeTrim(motivoSituacao));

            NaturezaJuridicaDTO nj = (naturezaJuridica == null && descricaoNaturezaJuridica == null) ? null
                    : new NaturezaJuridicaDTO(formatNaturezaCodigo(naturezaJuridica),
                                              safeTrim(descricaoNaturezaJuridica));

            CnaeDTO principal = cnaeFiscal == null ? null
                    : new CnaeDTO(digitsOnly(cnaeFiscal), safeTrim(cnaeFiscalDescricao));

            List<CnaeDTO> secundarias = new ArrayList<>();
            if (cnaesSecundarios != null) {
                for (ArchiveCnae c : cnaesSecundarios) {
                    if (c == null) continue;
                    secundarias.add(new CnaeDTO(digitsOnly(c.codigo), safeTrim(c.descricao)));
                }
            }

            EnderecoCompletoDTO endereco = new EnderecoCompletoDTO(
                    safeTrim(logradouro),
                    safeTrim(numero),
                    safeTrim(complemento),
                    safeTrim(bairro),
                    digitsOnly(cep),
                    municipio == null ? null : new MunicipioDTO(safeTrim(codigoMunicipioIbge), safeTrim(municipio)),
                    safeTrim(uf));

            List<TelefoneDTO> tels = new ArrayList<>();
            var t1 = splitTelefone(dddTel1);
            if (t1 != null) tels.add(t1);
            var t2 = splitTelefone(dddTel2);
            if (t2 != null) tels.add(t2);

            List<SocioDTO> socios = new ArrayList<>();
            if (qsa != null) {
                for (ArchiveSocio s : qsa) {
                    if (s == null) continue;
                    socios.add(new SocioDTO(
                            safeTrim(s.nome),
                            CnpjParseSupport.maskCpfCnpj(s.cpfCnpj),
                            safeTrim(s.qualificacao),
                            safeTrim(s.pais),
                            parseDate(s.dataEntrada)));
                }
            }
            if (nj != null && nj.isEmpresarioIndividual()) {
                socios = new ArrayList<>();
            }

            return new CnpjConsolidadoDTO(
                    digitsOnly(cnpj),
                    TipoEstabelecimento.fromCodigo(matrizFilial != null ? matrizFilial.toString() : null),
                    upperTrim(razaoSocial),
                    safeTrim(nomeFantasia),
                    sit,
                    nj,
                    parseDate(dataAbertura),
                    principal,
                    secundarias,
                    endereco,
                    new ContatosDTO(tels, safeTrim(email)),
                    parseCapitalCentavosInteiros(capitalSocial),
                    safeTrim(porte),
                    new InformacoesSimplesMeiDTO(opcaoSimples, opcaoMei, List.of()),
                    capQsa(socios),
                    List.of(PROVIDER_NAME)
            );
        }

        private static String formatNaturezaCodigo(String raw) {
            if (raw == null) return null;
            String d = raw.replaceAll("\\D", "");
            if (d.length() == 4) {
                return d.substring(0, 3) + "-" + d.substring(3);
            }
            return safeTrim(raw);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ArchiveCnae(String codigo, String descricao) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ArchiveSocio(
            String nome,
            @JsonProperty("cnpj_cpf_do_socio") String cpfCnpj,
            @JsonProperty("qualificacao_socio") String qualificacao,
            @JsonProperty("pais") String pais,
            @JsonProperty("data_entrada_sociedade") String dataEntrada) {}
}
