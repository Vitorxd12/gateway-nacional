package br.com.cernebr.gateway_nacional.juridico.sancoes.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.juridico.sancoes.dto.SancaoResponse;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * Cliente da API de Sanções da CGU no Portal da Transparência.
 *
 * <p><b>Desvio intencional do brief:</b> o brief original mencionou o path
 * {@code /api-de-dados/sancoes?cnpj=}; este endpoint não existe no Portal
 * da Transparência. A consulta canônica é
 * {@code /api-de-dados/ceis?cnpjSancionado=} para o Cadastro de Empresas
 * Inidôneas e Suspensas. Path e parâmetro ficam configuráveis via
 * {@code application.yml} para que produção possa apontar para CNEP
 * (Cadastro Nacional de Empresas Punidas) ou outras variações sem
 * mexer no código.</p>
 *
 * <p><b>Autenticação:</b> a API exige o header {@code chave-api-dados},
 * obtido via cadastro em {@code portaldatransparencia.gov.br/api-de-dados/cadastrar-email}.
 * A chave é injetada via env {@code GATEWAY_JURIDICO_SANCOES_CGU_API_KEY};
 * quando ausente, o cliente curto-circuita antes do round-trip HTTP e
 * lança {@link ResourceUnavailableException} com mensagem clara — evita
 * mascarar problema de configuração como falha de upstream.</p>
 */
@Slf4j
@Component
public class SancoesClient {

    public static final String PROVIDER_NAME = "CGU-PortalTransparencia";

    private static final ParameterizedTypeReference<List<CguSancaoPayload>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RestClient restClient;
    private final String path;
    private final String cnpjParam;
    private final String apiKey;

    public SancoesClient(RestClient.Builder builder,
                         @Value("${gateway.juridico.sancoes.cgu.base-url:https://api.portaldatransparencia.gov.br}") String baseUrl,
                         @Value("${gateway.juridico.sancoes.cgu.path:/api-de-dados/ceis}") String path,
                         @Value("${gateway.juridico.sancoes.cgu.cnpj-param:cnpjSancionado}") String cnpjParam,
                         @Value("${gateway.juridico.sancoes.cgu.api-key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.path = path;
        this.cnpjParam = cnpjParam;
        this.apiKey = apiKey;
    }

    @CircuitBreaker(name = "cguSancoesCB", fallbackMethod = "fallback")
    public List<SancaoResponse> fetchByCnpj(String cnpj) {
        if (apiKey == null || apiKey.isBlank()) {
            // Falhar antes de abrir socket dá ao operador uma mensagem
            // acionável; deixar a CGU responder 401 vira ruído no log do CB.
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "API de Sanções da CGU exige chave de acesso. Configure GATEWAY_JURIDICO_SANCOES_CGU_API_KEY após cadastrar e-mail em portaldatransparencia.gov.br/api-de-dados/cadastrar-email.");
        }

        log.debug("CGU CEIS lookup cnpj={}", cnpj);

        List<CguSancaoPayload> payload;
        try {
            payload = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(path)
                            .queryParam(cnpjParam, cnpj)
                            .queryParam("pagina", 1)
                            .build())
                    .header("chave-api-dados", apiKey)
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED || ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "CGU rejeitou a chave de acesso configurada (HTTP " + ex.getStatusCode().value() + "). Verifique GATEWAY_JURIDICO_SANCOES_CGU_API_KEY.", ex);
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CGU retornou HTTP " + ex.getStatusCode().value() + " ao consultar sanções.", ex);
        }

        if (payload == null || payload.isEmpty()) {
            // Diferente de 404: ausência de resultado é semanticamente
            // "CNPJ sem sanções publicadas" — informação útil. Devolve
            // lista vazia para o consumidor distinguir "limpo" de "indisponível".
            return Collections.emptyList();
        }

        return payload.stream().map(CguSancaoPayload::toResponse).toList();
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<SancaoResponse> fallback(String cnpj, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) {
            throw ru;
        }
        log.warn("CGU CEIS fallback cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "API CGU de Sanções indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Estrutura do payload do CEIS — campos aninhados refletem a hierarquia
     * dada pela CGU. Mantemos {@code @JsonIgnoreProperties(ignoreUnknown=true)}
     * para tolerar atributos que a CGU adiciona sem coordenação.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CguSancaoPayload(
            @JsonProperty("dataInicioSancao") String dataInicio,
            @JsonProperty("dataFimSancao") String dataFim,
            @JsonProperty("tipoSancao") TipoSancao tipoSancao,
            @JsonProperty("orgaoSancionador") OrgaoSancionador orgao,
            @JsonProperty("nomeSancionado") String nomeSancionado,
            @JsonProperty("cnpjSancionado") String cnpjSancionado
    ) {
        SancaoResponse toResponse() {
            return new SancaoResponse(
                    cnpjSancionado,
                    nomeSancionado,
                    tipoSancao != null ? tipoSancao.descricao() : null,
                    orgao != null ? orgao.nome() : null,
                    parseDate(dataInicio),
                    parseDate(dataFim)
            );
        }

        private static LocalDate parseDate(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                return LocalDate.parse(raw, BR_DATE);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TipoSancao(@JsonProperty("descricaoPortal") String descricao) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrgaoSancionador(@JsonProperty("nome") String nome) {
    }
}
