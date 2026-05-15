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
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseDate;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.safeTrim;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.upperTrim;
import static br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjParseSupport.parseCapitalReais;

/**
 * Provider secundário (dados complementares) — CNPJá!
 * ({@code https://open.cnpja.com/office/{cnpj}}).
 *
 * <p>Cobertura forte de tabela de qualificação de sócios e enquadramento
 * Simples/MEI consolidado por período (Receita/SECEX). Tier público gratuito.</p>
 */
@Slf4j
@Component
public class CnpjAClient implements CnpjConsolidadoClientProvider {

    public static final String PROVIDER_NAME = "CnpjA";

    private final RestClient restClient;

    public CnpjAClient(RestClient.Builder builder,
                       @Value("${gateway.cnpj.cnpja.base-url:https://open.cnpja.com}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "cnpjACB", fallbackMethod = "fallback")
    public CnpjConsolidadoDTO fetch(String cnpj) {
        CnpjAPayload payload = restClient.get()
                .uri("/office/{cnpj}", cnpj)
                .retrieve()
                .body(CnpjAPayload.class);

        if (payload == null || payload.taxId == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CNPJá retornou resposta vazia ou CNPJ não localizado.");
        }
        return payload.toConsolidado();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CnpjConsolidadoDTO fallback(String cnpj, Throwable cause) {
        log.warn("CNPJá fallback acionado cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "CNPJá indisponível, sob rate-limit ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CnpjAPayload(
            @JsonProperty("taxId") String taxId,
            @JsonProperty("alias") String alias,
            @JsonProperty("founded") String founded,
            Company company,
            Address address,
            List<Phone> phones,
            List<Email> emails,
            Activity mainActivity,
            List<Activity> sideActivities,
            Status status,
            @JsonProperty("statusDate") String statusDate,
            Reason reason
    ) {
        CnpjConsolidadoDTO toConsolidado() {
            TipoEstabelecimento tipo = (company != null && company.head != null && company.head)
                    ? TipoEstabelecimento.MATRIZ : TipoEstabelecimento.FILIAL;

            SituacaoCadastralDTO situacao = new SituacaoCadastralDTO(
                    status != null && status.id != null ? status.id.toString() : null,
                    status != null ? upperTrim(status.text) : null,
                    parseDate(statusDate),
                    reason == null ? null : safeTrim(reason.text)
            );

            NaturezaJuridicaDTO nj = (company == null || company.nature == null) ? null
                    : new NaturezaJuridicaDTO(
                            company.nature.id != null ? formatNaturezaCodigo(company.nature.id) : null,
                            safeTrim(company.nature.text));

            CnaeDTO principal = mainActivity == null ? null
                    : new CnaeDTO(
                            mainActivity.id != null ? mainActivity.id.toString() : null,
                            safeTrim(mainActivity.text));

            List<CnaeDTO> secundarias = new ArrayList<>();
            if (sideActivities != null) {
                for (Activity a : sideActivities) {
                    if (a == null) continue;
                    secundarias.add(new CnaeDTO(
                            a.id != null ? a.id.toString() : null,
                            safeTrim(a.text)));
                }
            }

            EnderecoCompletoDTO endereco = address == null ? null
                    : new EnderecoCompletoDTO(
                            safeTrim(address.street),
                            safeTrim(address.number),
                            safeTrim(address.details),
                            safeTrim(address.district),
                            digitsOnly(address.zip),
                            address.city == null ? null
                                    : new MunicipioDTO(
                                            address.city.ibgeId != null
                                                    ? address.city.ibgeId.toString() : null,
                                            safeTrim(address.city.name)),
                            address.state != null ? safeTrim(address.state.id) : null);

            List<TelefoneDTO> tels = new ArrayList<>();
            if (phones != null) {
                for (Phone p : phones) {
                    if (p == null) continue;
                    if (p.area != null && p.number != null) {
                        tels.add(new TelefoneDTO(digitsOnly(p.area), digitsOnly(p.number)));
                    }
                }
            }
            String email = (emails != null && !emails.isEmpty() && emails.get(0) != null)
                    ? safeTrim(emails.get(0).address) : null;

            List<SocioDTO> qsa = new ArrayList<>();
            if (company != null && company.members != null) {
                for (Member m : company.members) {
                    if (m == null || m.person == null) continue;
                    qsa.add(new SocioDTO(
                            safeTrim(m.person.name),
                            CnpjParseSupport.maskCpfCnpj(m.person.taxId),
                            safeTrim(m.role != null ? m.role.text : null),
                            m.person.country != null ? safeTrim(m.person.country.name) : null,
                            parseDate(m.since)));
                }
            }
            if (nj != null && nj.isEmpresarioIndividual()) {
                qsa = new ArrayList<>();
            }

            InformacoesSimplesMeiDTO simples = parseSimples();

            return new CnpjConsolidadoDTO(
                    digitsOnly(taxId),
                    tipo,
                    company != null ? upperTrim(company.name) : null,
                    safeTrim(alias),
                    situacao,
                    nj,
                    parseDate(founded),
                    principal,
                    secundarias,
                    endereco,
                    new ContatosDTO(tels, email),
                    company == null ? null : parseCapitalReais(company.equity),
                    company != null && company.size != null ? safeTrim(company.size.text) : null,
                    simples,
                    capQsa(qsa),
                    List.of(PROVIDER_NAME)
            );
        }

        private InformacoesSimplesMeiDTO parseSimples() {
            if (company == null || company.simples == null) return null;
            List<SimplesPeriodoDTO> periodos = new ArrayList<>();
            if (company.simples.since != null) {
                periodos.add(new SimplesPeriodoDTO("SIMPLES",
                        parseDate(company.simples.since), null));
            }
            return new InformacoesSimplesMeiDTO(
                    company.simples.optant,
                    company.simei != null ? company.simei.optant : null,
                    periodos);
        }

        private static String formatNaturezaCodigo(Number id) {
            String raw = String.format("%04d", id.intValue());
            if (raw.length() != 4) return id.toString();
            return raw.substring(0, 3) + "-" + raw.substring(3);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Company(
            String name,
            Boolean head,
            Nature nature,
            Size size,
            Object equity,
            Simples simples,
            Simples simei,
            List<Member> members) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Nature(Integer id, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Size(Integer id, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Simples(Boolean optant, String since) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Member(String since, Role role, Person person) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Role(Integer id, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Person(String name, String taxId, Country country) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Country(Integer id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Address(
            String street,
            String number,
            String details,
            String district,
            String zip,
            City city,
            State state) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record City(@JsonProperty("ibgeId") Integer ibgeId, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record State(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Phone(String type, String area, String number) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Email(String address) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Activity(Integer id, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Status(Integer id, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Reason(String text) {}
}
