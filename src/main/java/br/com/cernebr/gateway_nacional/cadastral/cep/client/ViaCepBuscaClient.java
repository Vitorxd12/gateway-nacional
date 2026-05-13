package br.com.cernebr.gateway_nacional.cadastral.cep.client;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Busca reversa de CEP por endereço via ViaCEP.
 *
 * <p>O ViaCEP expõe {@code GET /ws/{uf}/{cidade}/{logradouro}/json/} que retorna
 * uma lista de CEPs que correspondem ao endereço informado. Esta é a única fonte
 * gratuita e pública que suporta busca textual de CEP sem cadastro.</p>
 *
 * <h2>Parâmetros obrigatórios</h2>
 * <ul>
 *   <li>{@code uf} — sigla da UF em caixa alta (ex.: {@code SP})</li>
 *   <li>{@code cidade} — nome do município (acentos aceitos, URL-encoded pelo RestClient)</li>
 *   <li>{@code logradouro} — nome da rua/avenida. Mínimo de 3 caracteres exigido pela API.</li>
 * </ul>
 *
 * <h2>Resposta</h2>
 * <p>O ViaCEP retorna um array JSON com até 50 registros. Cada item tem os mesmos
 * campos do endpoint de consulta por CEP. O gateway normaliza cada item para o
 * {@link CepResponse} unificado.</p>
 *
 * <h2>Limites</h2>
 * <p>A API pública do ViaCEP não documenta rate limits, mas em produção de alto
 * volume recomenda-se não exceder ~100 req/min. O {@code viaCepBuscaCB} (CB próprio,
 * isolado do viaCepCB de consulta por CEP) protege a instância do gateway contra
 * falhas temporárias do upstream.</p>
 */
@Slf4j
@Component
public class ViaCepBuscaClient {

    public static final String PROVIDER_NAME = "ViaCEP-Busca";

    private final RestClient restClient;

    public ViaCepBuscaClient(
            RestClient.Builder builder,
            @Value("${gateway.cep.viacep.base-url:https://viacep.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Consulta o ViaCEP pelo endereço e retorna a lista de candidatos.
     *
     * @param uf          sigla da UF (ex.: "SP")
     * @param cidade      nome do município
     * @param logradouro  nome do logradouro (mínimo 3 caracteres)
     * @return lista (possivelmente vazia) de respostas de CEP
     * @throws ResourceUnavailableException quando o upstream falha ou o CB está aberto
     */
    @CircuitBreaker(name = "viaCepBuscaCB", fallbackMethod = "fallback")
    public List<CepResponse> buscarPorEndereco(String uf, String cidade, String logradouro) {
        List<ViaCepPayload> payloads = restClient.get()
                .uri("/ws/{uf}/{cidade}/{logradouro}/json/", uf, cidade, logradouro)
                .retrieve()
                // ViaCEP devolve 400 quando não encontra resultados — tratamos como lista vazia.
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    log.debug("ViaCEP busca retornou {} para uf={} cidade={} logradouro={}",
                            resp.getStatusCode().value(), uf, cidade, logradouro);
                    throw new EmptyResultException();
                })
                .body(LIST_PAYLOAD);

        if (payloads == null || payloads.isEmpty()) {
            return Collections.emptyList();
        }

        return payloads.stream()
                .filter(p -> !p.isInvalid())
                .map(ViaCepPayload::toCepResponse)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    private List<CepResponse> fallback(String uf, String cidade, String logradouro, Throwable cause) {
        if (cause instanceof EmptyResultException) {
            return Collections.emptyList();
        }
        log.warn("ViaCEP busca fallback uf={} cidade={} logradouro={} causa={}",
                uf, cidade, logradouro, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ViaCEP busca indisponível ou Circuit Breaker aberto.", cause);
    }

    // ── Payload interno ────────────────────────────────────────────────────────

    private static final ParameterizedTypeReference<List<ViaCepPayload>> LIST_PAYLOAD =
            new ParameterizedTypeReference<>() {};

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ViaCepPayload(
            String cep,
            String logradouro,
            String complemento,
            String bairro,
            String localidade,
            String uf,
            String ibge
    ) {
        boolean isInvalid() {
            return cep == null || cep.isBlank();
        }

        CepResponse toCepResponse() {
            return new CepResponse(cep, logradouro, complemento, bairro, localidade, uf, ibge);
        }
    }

    /** Sentinela interna para diferenciar 4xx (sem resultado) de erro de infra. */
    private static class EmptyResultException extends RuntimeException {
        EmptyResultException() {
            super("ViaCEP não encontrou CEPs para o endereço informado.");
        }
    }
}
