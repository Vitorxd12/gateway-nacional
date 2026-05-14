package br.com.cernebr.gateway_nacional.cadastral.cnpj.client;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnaeDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.ContatosDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.EnderecoCompletoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.InformacoesSimplesMeiDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.MunicipioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.NaturezaJuridicaDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SimplesPeriodoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SituacaoCadastralDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SocioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TelefoneDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TipoEstabelecimento;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.capQsa;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.digitsOnly;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseCapitalReais;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseDate;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseTipoEstabelecimento;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.safeTrim;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.upperTrim;

/**
 * Provider primário (alta velocidade) — CNPJ.WS público
 * ({@code https://publica.cnpj.ws/cnpj/{cnpj}}).
 *
 * <p>Payload riquíssimo (estabelecimento, atividades, sócios, simples_nacional)
 * que serve de espinha dorsal do merge. Não exige API key no tier público.</p>
 */
@Slf4j
@Component
public class CnpjWsClient implements CnpjConsolidadoClientProvider {

    public static final String PROVIDER_NAME = "CnpjWs";

    private final RestClient restClient;

    public CnpjWsClient(RestClient.Builder builder,
                        @Value("${gateway.cnpj.cnpjws.base-url:https://publica.cnpj.ws}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "cnpjWsCB", fallbackMethod = "fallback")
    public CnpjConsolidadoDTO fetch(String cnpj) {
        CnpjWsPayload payload = restClient.get()
                .uri("/cnpj/{cnpj}", cnpj)
                .retrieve()
                .body(CnpjWsPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CNPJ.WS retornou resposta vazia ou CNPJ não localizado.");
        }
        return payload.toConsolidado();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjConsolidadoDTO fallback(String cnpj, Throwable cause) {
        log.warn("CNPJ.WS fallback acionado cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "CNPJ.WS indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CnpjWsPayload(
            String cnpj_raiz,
            String razao_social,
            String capital_social,
            Porte porte,
            NaturezaJuridica natureza_juridica,
            SimplesNacional simples_nacional,
            List<Socio> socios,
            Estabelecimento estabelecimento
    ) {
        boolean isInvalid() {
            return estabelecimento == null || (estabelecimento.cnpj == null && cnpj_raiz == null);
        }

        CnpjConsolidadoDTO toConsolidado() {
            String ni = estabelecimento.cnpj != null
                    ? digitsOnly(estabelecimento.cnpj)
                    : digitsOnly(cnpj_raiz);

            TipoEstabelecimento tipo = parseTipoEstabelecimento(
                    estabelecimento.tipo_id != null ? estabelecimento.tipo_id.toString() : null,
                    estabelecimento.tipo);

            SituacaoCadastralDTO situacao = new SituacaoCadastralDTO(
                    estabelecimento.situacao_cadastral_id != null
                            ? estabelecimento.situacao_cadastral_id.toString()
                            : null,
                    upperTrim(estabelecimento.situacao_cadastral),
                    parseDate(estabelecimento.data_situacao_cadastral),
                    safeTrim(estabelecimento.motivo_situacao_cadastral != null
                            ? estabelecimento.motivo_situacao_cadastral.descricao
                            : null)
            );

            NaturezaJuridicaDTO nj = natureza_juridica == null ? null
                    : new NaturezaJuridicaDTO(safeTrim(natureza_juridica.id), safeTrim(natureza_juridica.descricao));

            CnaeDTO principal = estabelecimento.atividade_principal == null ? null
                    : new CnaeDTO(safeTrim(estabelecimento.atividade_principal.id),
                                  safeTrim(estabelecimento.atividade_principal.descricao));

            List<CnaeDTO> secundarias = new ArrayList<>();
            if (estabelecimento.atividades_secundarias != null) {
                for (Atividade a : estabelecimento.atividades_secundarias) {
                    if (a == null) continue;
                    secundarias.add(new CnaeDTO(safeTrim(a.id), safeTrim(a.descricao)));
                }
            }

            MunicipioDTO mun = estabelecimento.cidade == null ? null
                    : new MunicipioDTO(
                            estabelecimento.cidade.ibge_id != null
                                    ? estabelecimento.cidade.ibge_id.toString()
                                    : null,
                            safeTrim(estabelecimento.cidade.nome));

            EnderecoCompletoDTO endereco = new EnderecoCompletoDTO(
                    safeTrim(joinLogradouro(estabelecimento.tipo_logradouro, estabelecimento.logradouro)),
                    safeTrim(estabelecimento.numero),
                    safeTrim(estabelecimento.complemento),
                    safeTrim(estabelecimento.bairro),
                    digitsOnly(estabelecimento.cep),
                    mun,
                    estabelecimento.estado == null ? null : safeTrim(estabelecimento.estado.sigla)
            );

            List<TelefoneDTO> telefones = new ArrayList<>();
            if (estabelecimento.ddd1 != null && estabelecimento.telefone1 != null) {
                telefones.add(new TelefoneDTO(digitsOnly(estabelecimento.ddd1),
                        digitsOnly(estabelecimento.telefone1)));
            }
            if (estabelecimento.ddd2 != null && estabelecimento.telefone2 != null) {
                telefones.add(new TelefoneDTO(digitsOnly(estabelecimento.ddd2),
                        digitsOnly(estabelecimento.telefone2)));
            }
            ContatosDTO contatos = new ContatosDTO(telefones, safeTrim(estabelecimento.email));

            InformacoesSimplesMeiDTO simples = parseSimples();

            List<SocioDTO> qsa = new ArrayList<>();
            if (socios != null) {
                for (Socio s : socios) {
                    if (s == null) continue;
                    qsa.add(new SocioDTO(
                            safeTrim(s.nome),
                            CnpjParseSupport.maskCpfCnpj(s.cpf_cnpj_socio),
                            safeTrim(s.qualificacao_socio != null ? s.qualificacao_socio.descricao : null),
                            safeTrim(s.pais != null ? s.pais.nome : null),
                            parseDate(s.data_entrada)));
                }
            }
            if (nj != null && nj.isEmpresarioIndividual()) {
                qsa = new ArrayList<>();
            }

            return new CnpjConsolidadoDTO(
                    ni,
                    tipo,
                    upperTrim(razao_social),
                    safeTrim(estabelecimento.nome_fantasia),
                    situacao,
                    nj,
                    parseDate(estabelecimento.data_inicio_atividade),
                    principal,
                    secundarias,
                    endereco,
                    contatos,
                    parseCapitalReais(capital_social),
                    porte == null ? null : safeTrim(porte.descricao),
                    simples,
                    capQsa(qsa),
                    List.of(PROVIDER_NAME)
            );
        }

        private InformacoesSimplesMeiDTO parseSimples() {
            if (simples_nacional == null) return null;
            List<SimplesPeriodoDTO> periodos = new ArrayList<>();
            if (simples_nacional.data_opcao_simples != null) {
                periodos.add(new SimplesPeriodoDTO("SIMPLES",
                        parseDate(simples_nacional.data_opcao_simples),
                        parseDate(simples_nacional.data_exclusao_simples)));
            }
            if (simples_nacional.data_opcao_mei != null) {
                periodos.add(new SimplesPeriodoDTO("MEI",
                        parseDate(simples_nacional.data_opcao_mei),
                        parseDate(simples_nacional.data_exclusao_mei)));
            }
            return new InformacoesSimplesMeiDTO(
                    simples_nacional.simples,
                    simples_nacional.mei,
                    periodos);
        }

        private static String joinLogradouro(String tipoLogradouro, String logradouro) {
            if (tipoLogradouro == null || tipoLogradouro.isBlank()) return logradouro;
            if (logradouro == null) return tipoLogradouro;
            return (tipoLogradouro + " " + logradouro).trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Porte(String id, String descricao) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaturezaJuridica(String id, String descricao) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SimplesNacional(
            Boolean simples,
            Boolean mei,
            String data_opcao_simples,
            String data_exclusao_simples,
            String data_opcao_mei,
            String data_exclusao_mei) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Socio(
            String nome,
            String cpf_cnpj_socio,
            Qualificacao qualificacao_socio,
            Pais pais,
            String data_entrada) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Qualificacao(String descricao) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pais(String nome) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MotivoSituacao(String descricao) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Estado(String sigla) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Cidade(Long ibge_id, String nome) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Atividade(String id, String descricao) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Estabelecimento(
            String cnpj,
            Integer tipo_id,
            String tipo,
            String nome_fantasia,
            Integer situacao_cadastral_id,
            String situacao_cadastral,
            String data_situacao_cadastral,
            MotivoSituacao motivo_situacao_cadastral,
            String tipo_logradouro,
            String logradouro,
            String numero,
            String complemento,
            String bairro,
            String cep,
            Cidade cidade,
            Estado estado,
            String ddd1,
            String telefone1,
            String ddd2,
            String telefone2,
            String email,
            Atividade atividade_principal,
            List<Atividade> atividades_secundarias,
            String data_inicio_atividade) {}
}
