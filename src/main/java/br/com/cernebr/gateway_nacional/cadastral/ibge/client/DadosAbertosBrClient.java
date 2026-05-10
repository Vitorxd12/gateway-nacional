package br.com.cernebr.gateway_nacional.cadastral.ibge.client;

import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider alternativo de municípios — {@code educacao.dadosabertosbr.com/api/cidades/{uf}}.
 *
 * <p>Endpoint legado usado pela BrasilAPI. Devolve um array de strings no
 * formato {@code "{codigo_ibge}:{nome}"} — sem JSON aninhado, parsing trivial.
 * Hospedado em HTTP (não HTTPS) na URL original; preservamos isso pra paridade,
 * mas pode ser sobrescrito via {@code gateway.ibge.dados-abertos-br.base-url}
 * caso uma versão HTTPS apareça.</p>
 *
 * <p>Entra no hedge com {@link IbgeGovClient} para a operação de listagem de
 * municípios — {@code IbgeGovClient} costuma ser mais rápido (CDN gov.br),
 * mas este aqui já salvou requests quando o servicodados ficou intermitente.</p>
 */
@Slf4j
@Component
public class DadosAbertosBrClient implements IbgeMunicipiosClientProvider {

    public static final String PROVIDER_NAME = "DadosAbertosBR";

    private final RestClient restClient;

    public DadosAbertosBrClient(RestClient.Builder builder,
                                @Value("${gateway.ibge.dados-abertos-br.base-url:http://educacao.dadosabertosbr.com}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "dadosAbertosBrCB", fallbackMethod = "fallback")
    public List<MunicipioResponse> fetchByUf(String siglaUf) {
        List<String> raw = restClient.get()
                .uri("/api/cidades/{uf}", siglaUf)
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {});

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "DadosAbertosBR retornou lista vazia para UF " + siglaUf);
        }

        List<MunicipioResponse> municipios = new ArrayList<>(raw.size());
        for (String entry : raw) {
            // Formato esperado: "{codigo_ibge}:{nome}"
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                continue; // entrada mal-formada, ignora
            }
            String codigo = entry.substring(0, colon);
            String nome = entry.substring(colon + 1);
            municipios.add(new MunicipioResponse(nome, codigo));
        }
        if (municipios.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "DadosAbertosBR retornou todas as linhas mal-formadas para UF " + siglaUf);
        }
        return municipios;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<MunicipioResponse> fallback(String siglaUf, Throwable cause) {
        log.warn("DadosAbertosBR fallback for uf={} cause={}", siglaUf, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "DadosAbertosBR indisponível ou Circuit Breaker aberto.", cause);
    }
}
