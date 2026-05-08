package br.com.cernebr.gateway_nacional.fiscal.ncm.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.fiscal.ncm.dto.NcmResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Secondary NCM provider — Portal Único Siscomex
 * ({@code https://portalunico.siscomex.gov.br}).
 *
 * <h2>Status do contrato</h2>
 * <p>Em 2026-05-08 confirmamos via probe direto que o caminho público
 * tradicional ({@code /classif/api/publico/nomenclatura/{codigo}}) responde
 * 404 com mensagem RESTEASY003210 — o endpoint foi movido ou
 * descontinuado e o caminho atualizado ainda não está documentado
 * publicamente. As variantes próximas que tentamos
 * ({@code /ncm/{codigo}}, {@code /nomenclatura/v1/{codigo}}, query-param
 * {@code ?codigo=}) também 404. <b>Como fallback, este cliente trip o
 * Circuit Breaker hoje em todo cenário</b>; a estrutura está pronta para
 * absorver o path correto via property
 * {@code gateway.ncm.siscomex.codigo-path} sem mudança de código.</p>
 *
 * <h2>Filosofia</h2>
 * <p>Mantemos este cliente na cascata mesmo enquanto não funciona porque:
 * <ol>
 *   <li>O dia em que o Siscomex documentar o novo path, atualizar uma
 *       linha do {@code application.yml} restaura a redundância;</li>
 *   <li>Fechar o cliente hoje exigiria reabri-lo amanhã, com risco de
 *       drift de configuração CB/timeouts entre as ressuscitações;</li>
 *   <li>O CB já protege o gateway: se o Siscomex retorna 404, a falha é
 *       contada e a cascata do {@code NcmService} continua para outros
 *       provedores futuros sem latência adicional.</li>
 * </ol>
 */
@Slf4j
@Component
public class SiscomexNcmClient implements NcmClientProvider {

    public static final String PROVIDER_NAME = "Siscomex";

    private final RestClient restClient;
    private final String codigoPath;

    public SiscomexNcmClient(RestClient.Builder builder,
                             @Value("${gateway.ncm.siscomex.base-url:https://portalunico.siscomex.gov.br}") String baseUrl,
                             @Value("${gateway.ncm.siscomex.codigo-path:/classif/api/publico/nomenclatura/{codigo}}") String codigoPath) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.codigoPath = codigoPath;
    }

    @Override
    @CircuitBreaker(name = "siscomexNcmCB", fallbackMethod = "findByCodigoFallback")
    public Optional<NcmResponse> findByCodigo(String codigo) {
        try {
            SiscomexNcmPayload payload = restClient.get()
                    .uri(codigoPath, codigo)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {})
                    .body(SiscomexNcmPayload.class);

            if (payload == null || payload.codigo() == null) {
                return Optional.empty();
            }
            return Optional.of(payload.toResponse());
        } catch (RestClientResponseException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Siscomex devolveu " + ex.getStatusCode() + " para NCM " + codigo + ".", ex);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Siscomex inacessível para NCM " + codigo + ": " + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * O Portal Único Siscomex não publicou um endpoint de busca textual
     * equivalente ao {@code ?search=} do BrasilAPI no momento da escrita.
     * Para preservar a interface, devolvemos lista vazia e o Service
     * deixa o BrasilAPI ser a única fonte de busca.
     */
    @Override
    public List<NcmResponse> searchByDescricao(String descricao) {
        return Collections.emptyList();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private Optional<NcmResponse> findByCodigoFallback(String codigo, Throwable cause) {
        log.warn("Siscomex fallback (findByCodigo={}): {}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Siscomex indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Wire-shape of the Portal Único Siscomex payload.
     *
     * <p>The exact field naming will need to be re-checked when the
     * upstream documents the new contract. The mapping below assumes
     * camelCase like the rest of the Portal Único — adjust if needed.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SiscomexNcmPayload(
            String codigo,
            String descricao,
            @JsonProperty("dataInicio") LocalDate dataInicio,
            @JsonProperty("dataFim") LocalDate dataFim,
            @JsonProperty("tipoAto") String tipoAto,
            @JsonProperty("numeroAto") String numeroAto,
            @JsonProperty("anoAto") Integer anoAto
    ) {
        NcmResponse toResponse() {
            return new NcmResponse(codigo, descricao, dataInicio, dataFim, tipoAto, numeroAto, anoAto);
        }
    }
}
