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
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TipoEstabelecimento;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.capQsa;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.digitsOnly;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseCapitalReais;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseDate;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.safeTrim;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.splitTelefone;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.upperTrim;

/**
 * Provider de fallback controlado — ReceitaWS ({@code https://www.receitaws.com.br}).
 *
 * <p><b>Por que é fallback e não primary:</b> tier gratuito da ReceitaWS
 * rate-limita agressivamente em HTTP 429. O bucket local
 * ({@code 3 req/min}) garante que <em>nunca</em> excedemos o teto público
 * publicado pelo provider, mesmo sob bursts do gateway. Quando o bucket
 * está vazio, o client devolve {@link ResourceUnavailableException} sem
 * round-trip, mantendo a quota intacta para a próxima janela.</p>
 *
 * <p>Mesmo sob hard-cap, o payload ReceitaWS é considerado o mais "oficial"
 * por agregar período de Simples/MEI, qualificação completa de sócios e
 * descrição humana de natureza jurídica — quando entra na consolidação,
 * costuma sobrescrever campos parciais dos primários.</p>
 */
@Slf4j
@Component
public class ReceitaWsConsolidadoClient implements CnpjConsolidadoClientProvider {

    public static final String PROVIDER_NAME = "ReceitaWS";
    private static final String STATUS_ERROR = "ERROR";

    private final RestClient restClient;
    private final Bucket bucket;

    public ReceitaWsConsolidadoClient(RestClient.Builder builder,
                                      @Value("${gateway.cnpj.receitaws.base-url:https://www.receitaws.com.br}") String baseUrl,
                                      @Value("${gateway.cnpj.receitaws.requests-per-minute:3}") long requestsPerMinute) {
        this.restClient = builder.baseUrl(baseUrl).build();
        // Bucket4j local — token bucket in-memory, dimensionado por requestsPerMinute.
        // Não compartilha estado entre pods, o que é desejado: cada pod respeita
        // seu próprio teto, e o número de pods em produção (≤4) ainda fica abaixo
        // do limite agregado por IP que a ReceitaWS observa.
        this.bucket = Bucket.builder()
                .addLimit(Bandwidth.simple(requestsPerMinute, Duration.ofMinutes(1)))
                .build();
    }

    @Override
    @CircuitBreaker(name = "receitaWsCB", fallbackMethod = "fallback")
    public CnpjConsolidadoDTO fetch(String cnpj) {
        if (!bucket.tryConsume(1)) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ReceitaWS rate-limit local atingido (≤3 req/min) — preservando quota pública.");
        }
        ReceitaWsPayload payload = restClient.get()
                .uri("/v1/cnpj/{cnpj}", cnpj)
                .retrieve()
                .body(ReceitaWsPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ReceitaWS retornou resposta vazia, com erro ou CNPJ não localizado.");
        }
        return payload.toConsolidado();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjConsolidadoDTO fallback(String cnpj, Throwable cause) {
        log.warn("ReceitaWS fallback acionado cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ReceitaWS indisponível, sob rate-limit ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReceitaWsPayload(
            String status,
            String cnpj,
            String tipo,
            String abertura,
            String nome,
            String fantasia,
            String porte,
            String natureza_juridica,
            String logradouro,
            String numero,
            String complemento,
            String municipio,
            String bairro,
            String uf,
            String cep,
            String email,
            String telefone,
            String data_situacao,
            String motivo_situacao,
            String situacao_especial,
            String data_situacao_especial,
            String situacao,
            String capital_social,
            String opcao_pelo_simples,
            String data_opcao_pelo_simples,
            String data_exclusao_do_simples,
            String opcao_pelo_mei,
            String data_opcao_pelo_mei,
            String data_exclusao_do_mei,
            List<Atividade> atividade_principal,
            List<Atividade> atividades_secundarias,
            List<QsaEntry> qsa
    ) {
        boolean isInvalid() {
            return STATUS_ERROR.equalsIgnoreCase(status)
                    || cnpj == null || cnpj.isBlank();
        }

        CnpjConsolidadoDTO toConsolidado() {
            SituacaoCadastralDTO sit = new SituacaoCadastralDTO(
                    null,
                    upperTrim(situacao),
                    parseDate(data_situacao),
                    safeTrim(motivo_situacao));

            NaturezaJuridicaDTO nj = parseNaturezaJuridica();
            CnaeDTO principal = (atividade_principal == null || atividade_principal.isEmpty())
                    ? null
                    : new CnaeDTO(digitsOnly(atividade_principal.get(0).code()),
                                  safeTrim(atividade_principal.get(0).text()));

            List<CnaeDTO> secundarias = new ArrayList<>();
            if (atividades_secundarias != null) {
                for (Atividade a : atividades_secundarias) {
                    if (a == null) continue;
                    secundarias.add(new CnaeDTO(digitsOnly(a.code()), safeTrim(a.text())));
                }
            }

            List<SocioDTO> socios = new ArrayList<>();
            if (qsa != null) {
                for (QsaEntry s : qsa) {
                    if (s == null) continue;
                    socios.add(new SocioDTO(
                            safeTrim(s.nome),
                            CnpjParseSupport.maskCpfCnpj(s.cpf_cnpj_socio),
                            safeTrim(s.qual),
                            "BRASIL",
                            parseDate(s.data_entrada)));
                }
            }
            if (nj != null && nj.isEmpresarioIndividual()) {
                socios = new ArrayList<>();
            }

            List<br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TelefoneDTO> tels = new ArrayList<>();
            var phone = splitTelefone(telefone);
            if (phone != null) tels.add(phone);

            List<SimplesPeriodoDTO> periodos = new ArrayList<>();
            if (data_opcao_pelo_simples != null) {
                periodos.add(new SimplesPeriodoDTO("SIMPLES",
                        parseDate(data_opcao_pelo_simples),
                        parseDate(data_exclusao_do_simples)));
            }
            if (data_opcao_pelo_mei != null) {
                periodos.add(new SimplesPeriodoDTO("MEI",
                        parseDate(data_opcao_pelo_mei),
                        parseDate(data_exclusao_do_mei)));
            }
            InformacoesSimplesMeiDTO simples = new InformacoesSimplesMeiDTO(
                    parseBool(opcao_pelo_simples),
                    parseBool(opcao_pelo_mei),
                    periodos);

            return new CnpjConsolidadoDTO(
                    digitsOnly(cnpj),
                    TipoEstabelecimento.fromText(tipo),
                    upperTrim(nome),
                    safeTrim(fantasia),
                    sit,
                    nj,
                    parseDate(abertura),
                    principal,
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
                    parseCapitalReais(capital_social),
                    safeTrim(porte),
                    simples,
                    capQsa(socios),
                    List.of(PROVIDER_NAME)
            );
        }

        private NaturezaJuridicaDTO parseNaturezaJuridica() {
            if (natureza_juridica == null || natureza_juridica.isBlank()) return null;
            String value = natureza_juridica.trim();
            int dash = value.indexOf(" - ");
            if (dash > 0) {
                return new NaturezaJuridicaDTO(value.substring(0, dash).trim(),
                        value.substring(dash + 3).trim());
            }
            return new NaturezaJuridicaDTO(null, value);
        }

        private Boolean parseBool(String value) {
            if (value == null) return null;
            String v = value.trim().toUpperCase();
            if (v.isEmpty()) return null;
            return v.equals("SIM") || v.equals("TRUE") || v.startsWith("S");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Atividade(String code, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QsaEntry(
            String nome,
            String qual,
            String cpf_cnpj_socio,
            String data_entrada) {}
}
