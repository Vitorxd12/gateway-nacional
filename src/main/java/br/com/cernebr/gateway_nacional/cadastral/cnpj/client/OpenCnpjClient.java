package br.com.cernebr.gateway_nacional.cadastral.cnpj.client;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnaeDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.ContatosDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.EnderecoCompletoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.MunicipioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.NaturezaJuridicaDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SimplesPeriodoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SituacaoCadastralDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SocioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TelefoneDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TipoEstabelecimento;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.InformacoesSimplesMeiDTO;
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
 * Provider primário (alta velocidade) — OpenCNPJ ({@code https://api.opencnpj.org}).
 *
 * <p>Snapshot estático servido por CDN: latência ~50ms, sem rate-limit. Boa
 * espinha dorsal redundante ao CnpjWs. Capital social entregue como inteiro
 * em centavos — divisão por 100 obrigatória.</p>
 */
@Slf4j
@Component
public class OpenCnpjClient implements CnpjConsolidadoClientProvider {

    public static final String PROVIDER_NAME = "OpenCnpj";

    private final RestClient restClient;

    public OpenCnpjClient(RestClient.Builder builder,
                          @Value("${gateway.cnpj.opencnpj.base-url:https://api.opencnpj.org}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "openCnpjCB", fallbackMethod = "fallback")
    public CnpjConsolidadoDTO fetch(String cnpj) {
        OpenCnpjPayload payload = restClient.get()
                .uri("/{cnpj}", cnpj)
                .retrieve()
                .body(OpenCnpjPayload.class);

        if (payload == null || payload.cnpj == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "OpenCNPJ retornou resposta vazia ou CNPJ não localizado.");
        }
        return payload.toConsolidado();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjConsolidadoDTO fallback(String cnpj, Throwable cause) {
        log.warn("OpenCNPJ fallback acionado cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "OpenCNPJ indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenCnpjPayload(
            String cnpj,
            @JsonProperty("razao_social") String razaoSocial,
            @JsonProperty("nome_fantasia") String nomeFantasia,
            @JsonProperty("situacao_cadastral") String situacaoCadastral,
            @JsonProperty("data_situacao_cadastral") String dataSituacao,
            @JsonProperty("motivo_situacao_cadastral") String motivoSituacao,
            @JsonProperty("data_inicio_atividade") String dataAbertura,
            @JsonProperty("matriz_filial") String matrizFilial,
            @JsonProperty("cnae_principal") String cnaePrincipal,
            @JsonProperty("cnaes_secundarios") List<String> cnaesSecundarios,
            @JsonProperty("cnaes_secundarios_descricao") List<String> cnaesSecundariosDescricao,
            @JsonProperty("natureza_juridica") String naturezaJuridica,
            @JsonProperty("logradouro") String logradouro,
            @JsonProperty("numero") String numero,
            @JsonProperty("complemento") String complemento,
            @JsonProperty("bairro") String bairro,
            @JsonProperty("cep") String cep,
            @JsonProperty("uf") String uf,
            @JsonProperty("municipio") String municipio,
            @JsonProperty("email") String email,
            @JsonProperty("telefones") List<TelefoneEntry> telefones,
            @JsonProperty("capital_social") Object capitalSocial,
            @JsonProperty("porte_empresa") String porte,
            @JsonProperty("opcao_simples") String opcaoSimples,
            @JsonProperty("data_opcao_simples") String dataOpcaoSimples,
            @JsonProperty("opcao_mei") String opcaoMei,
            @JsonProperty("data_opcao_mei") String dataOpcaoMei,
            @JsonProperty("QSA") List<QsaEntry> qsa
    ) {
        CnpjConsolidadoDTO toConsolidado() {
            List<CnaeDTO> secundarias = new ArrayList<>();
            if (cnaesSecundarios != null) {
                for (int i = 0; i < cnaesSecundarios.size(); i++) {
                    String code = cnaesSecundarios.get(i);
                    String desc = (cnaesSecundariosDescricao != null
                            && i < cnaesSecundariosDescricao.size())
                            ? cnaesSecundariosDescricao.get(i) : null;
                    secundarias.add(new CnaeDTO(safeTrim(code), safeTrim(desc)));
                }
            }

            List<TelefoneDTO> tels = new ArrayList<>();
            if (telefones != null) {
                for (TelefoneEntry t : telefones) {
                    if (t == null) continue;
                    if (t.ddd != null && t.numero != null) {
                        tels.add(new TelefoneDTO(digitsOnly(t.ddd), digitsOnly(t.numero)));
                    } else if (t.telefone != null) {
                        TelefoneDTO parsed = splitTelefone(t.telefone);
                        if (parsed != null) tels.add(parsed);
                    }
                }
            }

            List<SocioDTO> socios = new ArrayList<>();
            if (qsa != null) {
                for (QsaEntry s : qsa) {
                    if (s == null) continue;
                    socios.add(new SocioDTO(
                            safeTrim(s.nome),
                            CnpjParseSupport.maskCpfCnpj(s.cpfCnpj != null ? s.cpfCnpj : s.documento),
                            safeTrim(s.qualificacao),
                            safeTrim(s.pais),
                            parseDate(s.dataEntrada)));
                }
            }
            NaturezaJuridicaDTO nj = parseNaturezaJuridica();
            if (nj != null && nj.isEmpresarioIndividual()) {
                socios = new ArrayList<>();
            }

            return new CnpjConsolidadoDTO(
                    digitsOnly(cnpj),
                    TipoEstabelecimento.fromText(matrizFilial),
                    upperTrim(razaoSocial),
                    safeTrim(nomeFantasia),
                    new SituacaoCadastralDTO(null, upperTrim(situacaoCadastral),
                            parseDate(dataSituacao), safeTrim(motivoSituacao)),
                    nj,
                    parseDate(dataAbertura),
                    parseCnae(cnaePrincipal, null),
                    secundarias,
                    new EnderecoCompletoDTO(
                            safeTrim(logradouro),
                            safeTrim(numero),
                            safeTrim(complemento),
                            safeTrim(bairro),
                            digitsOnly(cep),
                            municipio != null ? new MunicipioDTO(null, safeTrim(municipio)) : null,
                            safeTrim(uf)),
                    new ContatosDTO(tels, safeTrim(email)),
                    parseCapitalCentavosInteiros(capitalSocial),
                    safeTrim(porte),
                    parseSimples(),
                    capQsa(socios),
                    List.of(PROVIDER_NAME)
            );
        }

        private NaturezaJuridicaDTO parseNaturezaJuridica() {
            if (naturezaJuridica == null || naturezaJuridica.isBlank()) return null;
            String value = naturezaJuridica.trim();
            int dash = value.indexOf('-');
            int space = value.indexOf(' ');
            int idx = (dash > 0 && (space < 0 || dash < space)) ? dash : space;
            if (idx > 0) {
                return new NaturezaJuridicaDTO(value.substring(0, idx).trim(),
                        value.substring(idx + 1).trim());
            }
            return new NaturezaJuridicaDTO(null, value);
        }

        private CnaeDTO parseCnae(String code, String desc) {
            if (code == null || code.isBlank()) return null;
            return new CnaeDTO(safeTrim(code), safeTrim(desc));
        }

        private InformacoesSimplesMeiDTO parseSimples() {
            Boolean simples = parseBool(opcaoSimples);
            Boolean mei = parseBool(opcaoMei);
            List<SimplesPeriodoDTO> periodos = new ArrayList<>();
            if (dataOpcaoSimples != null) {
                periodos.add(new SimplesPeriodoDTO("SIMPLES",
                        parseDate(dataOpcaoSimples), null));
            }
            if (dataOpcaoMei != null) {
                periodos.add(new SimplesPeriodoDTO("MEI",
                        parseDate(dataOpcaoMei), null));
            }
            if (simples == null && mei == null && periodos.isEmpty()) return null;
            return new InformacoesSimplesMeiDTO(simples, mei, periodos);
        }

        private Boolean parseBool(String value) {
            if (value == null) return null;
            String v = value.trim().toUpperCase();
            return v.startsWith("S") || v.equals("TRUE") || v.equals("SIM");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TelefoneEntry(String ddd, String numero, String telefone) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QsaEntry(
            String nome,
            @JsonProperty("cpf_cnpj_socio") String cpfCnpj,
            String documento,
            String qualificacao,
            String pais,
            @JsonProperty("data_entrada_sociedade") String dataEntrada) {}
}
