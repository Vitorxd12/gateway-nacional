package br.com.cernebr.gateway_nacional.cadastral.ibge.client;

import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Provider IBGE oficial — {@code servicodados.ibge.gov.br/api/v1/localidades}.
 * Cobre as 4 operações que o domínio precisa:
 * <ul>
 *   <li>{@link #listAllUfs()} — todos os estados</li>
 *   <li>{@link #findUfByCodeOrSigla(String)} — uma UF (aceita id numérico ou sigla)</li>
 *   <li>{@link #fetchByUf(String)} — municípios de uma UF (implementa
 *       {@link IbgeMunicipiosClientProvider} para entrar no hedge)</li>
 *   <li>{@link #estimatePopulationByUfCode(int)} — população via agregado v3
 *       (id 6579, variável 9324)</li>
 * </ul>
 *
 * <p>O mapa de capitais é embutido aqui (idêntico ao {@code services/ibge/gov.js}
 * da BrasilAPI) porque o IBGE não publica a capital no payload de UF — é uma
 * informação fixa, mudou pela última vez em 1991 (reaproveitamento do antigo
 * Estado da Guanabara → RJ).</p>
 */
@Slf4j
@Component
public class IbgeGovClient implements IbgeMunicipiosClientProvider {

    public static final String PROVIDER_NAME = "IBGE-Gov";

    private static final String LOCALIDADES_PATH = "/api/v1/localidades/estados";
    private static final String AGREGADOS_PATH =
            "/api/v3/agregados/6579/periodos/-1/variaveis";
    private static final int POPULATION_VARIABLE_ID = 9324;

    /**
     * Capitais fixas das 27 UFs. Sincronizado com o mapa do
     * {@code services/ibge/gov.js} da BrasilAPI.
     */
    private static final Map<String, String> CAPITAIS = Map.ofEntries(
            Map.entry("AC", "Rio Branco"), Map.entry("AL", "Maceió"),
            Map.entry("AP", "Macapá"),     Map.entry("AM", "Manaus"),
            Map.entry("BA", "Salvador"),   Map.entry("CE", "Fortaleza"),
            Map.entry("DF", "Brasília"),   Map.entry("ES", "Vitória"),
            Map.entry("GO", "Goiânia"),    Map.entry("MA", "São Luís"),
            Map.entry("MT", "Cuiabá"),     Map.entry("MS", "Campo Grande"),
            Map.entry("MG", "Belo Horizonte"), Map.entry("PA", "Belém"),
            Map.entry("PB", "João Pessoa"), Map.entry("PR", "Curitiba"),
            Map.entry("PE", "Recife"),     Map.entry("PI", "Teresina"),
            Map.entry("RJ", "Rio de Janeiro"), Map.entry("RN", "Natal"),
            Map.entry("RS", "Porto Alegre"), Map.entry("RO", "Porto Velho"),
            Map.entry("RR", "Boa Vista"),  Map.entry("SC", "Florianópolis"),
            Map.entry("SP", "São Paulo"),  Map.entry("SE", "Aracaju"),
            Map.entry("TO", "Palmas")
    );

    private final RestClient restClient;

    public IbgeGovClient(RestClient.Builder builder,
                         @Value("${gateway.ibge.gov.base-url:https://servicodados.ibge.gov.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "ibgeGovUfCB", fallbackMethod = "fallbackListUfs")
    public List<UfResponse> listAllUfs() {
        List<UfPayload> raw = restClient.get()
                .uri(LOCALIDADES_PATH)
                .retrieve()
                .body(new ParameterizedTypeReference<List<UfPayload>>() {});

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "IBGE-Gov retornou lista vazia para UFs.");
        }
        return raw.stream().map(UfPayload::toUnified).toList();
    }

    @CircuitBreaker(name = "ibgeGovUfCB", fallbackMethod = "fallbackFindUf")
    public UfResponse findUfByCodeOrSigla(String codeOrSigla) {
        UfPayload raw;
        try {
            raw = restClient.get()
                    .uri(LOCALIDADES_PATH + "/{code}", codeOrSigla)
                    .retrieve()
                    .body(UfPayload.class);
        } catch (HttpClientErrorException.NotFound nf) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "IBGE-Gov não localizou UF: " + codeOrSigla, nf);
        }
        if (raw == null || raw.id() == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "IBGE-Gov retornou corpo vazio para UF: " + codeOrSigla);
        }
        return raw.toUnified();
    }

    @Override
    @CircuitBreaker(name = "ibgeGovMunicipiosCB", fallbackMethod = "fallbackMunicipios")
    public List<MunicipioResponse> fetchByUf(String siglaUf) {
        List<MunicipioPayload> raw = restClient.get()
                .uri(LOCALIDADES_PATH + "/{uf}/municipios", siglaUf)
                .retrieve()
                .body(new ParameterizedTypeReference<List<MunicipioPayload>>() {});

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "IBGE-Gov retornou lista vazia de municípios para UF " + siglaUf);
        }
        return raw.stream()
                .map(m -> new MunicipioResponse(m.nome(), String.valueOf(m.id())))
                .toList();
    }

    /**
     * Consulta o agregado 6579 do IBGE (variável 9324 = população residente
     * estimada). Retorna {@code null} em qualquer falha — a chamada é
     * <em>best-effort</em>: o detalhe da UF tem valor mesmo sem população.
     */
    @CircuitBreaker(name = "ibgePopulacaoCB", fallbackMethod = "fallbackPopulation")
    public PopulacaoResult estimatePopulationByUfCode(int ufCode) {
        List<AgregadoPayload> raw = restClient.get()
                .uri(uri -> uri.path(AGREGADOS_PATH)
                        .queryParam("localidades", "N3[" + ufCode + "]")
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<AgregadoPayload>>() {});

        if (raw == null || raw.isEmpty()) return null;

        return raw.stream()
                .filter(a -> a.id() != null && Integer.parseInt(a.id()) == POPULATION_VARIABLE_ID)
                .findFirst()
                .flatMap(a -> a.firstSeriePoint())
                .orElse(null);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<UfResponse> fallbackListUfs(Throwable cause) {
        log.warn("IBGE-Gov listAllUfs fallback: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "IBGE-Gov indisponível ou Circuit Breaker aberto (listAllUfs).", cause);
    }

    @SuppressWarnings("unused")
    private UfResponse fallbackFindUf(String codeOrSigla, Throwable cause) {
        log.warn("IBGE-Gov findUf fallback for code={} cause={}", codeOrSigla, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "IBGE-Gov indisponível ou Circuit Breaker aberto (findUf).", cause);
    }

    @SuppressWarnings("unused")
    private List<MunicipioResponse> fallbackMunicipios(String siglaUf, Throwable cause) {
        log.warn("IBGE-Gov municipios fallback for uf={} cause={}", siglaUf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "IBGE-Gov indisponível ou Circuit Breaker aberto (municipios).", cause);
    }

    @SuppressWarnings("unused")
    private PopulacaoResult fallbackPopulation(int ufCode, Throwable cause) {
        log.debug("IBGE-Gov populacao best-effort fallback for code={}: {}", ufCode, cause.toString());
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UfPayload(Integer id, String sigla, String nome, RegiaoPayload regiao) {
        UfResponse toUnified() {
            return new UfResponse(
                    id, sigla, nome,
                    regiao == null ? null : regiao.sigla(),
                    regiao == null ? null : regiao.nome(),
                    sigla == null ? null : CAPITAIS.get(sigla.toUpperCase())
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegiaoPayload(Integer id, String sigla, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MunicipioPayload(Long id, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AgregadoPayload(String id, List<ResultadoPayload> resultados) {
        java.util.Optional<PopulacaoResult> firstSeriePoint() {
            if (resultados == null || resultados.isEmpty()) return java.util.Optional.empty();
            ResultadoPayload r = resultados.get(0);
            if (r.series() == null || r.series().isEmpty()) return java.util.Optional.empty();
            Map<String, String> serie = r.series().get(0).serie();
            if (serie == null || serie.isEmpty()) return java.util.Optional.empty();
            return serie.entrySet().stream().findFirst()
                    .map(e -> new PopulacaoResult(parseLong(e.getValue()), e.getKey()));
        }

        private static Long parseLong(String s) {
            try { return Long.parseLong(s); } catch (Exception ex) { return null; }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResultadoPayload(List<SeriePayload> series) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeriePayload(Map<String, String> serie) {
    }

    public record PopulacaoResult(Long populacaoEstimada, String periodo) {
    }
}
