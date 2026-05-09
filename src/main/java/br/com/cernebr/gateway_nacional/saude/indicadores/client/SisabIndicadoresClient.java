package br.com.cernebr.gateway_nacional.saude.indicadores.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.indicadores.dto.IndicadorSinteticoResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Cliente do extrator de indicadores Previne Brasil/PMA.
 *
 * <p><b>Por que sidecar:</b> o portal SISAB do DataSUS expõe os indicadores
 * em uma cascata JSF/PrimeFaces com Bootstrap Multiselect — o estado dos
 * filtros só sincroniza via cliques reais no DOM, então scraping em HTTP
 * puro quebra a cada deploy do portal. O sidecar Python (Selenium +
 * Chromium headless) já existe para o módulo SISAB Validação; aqui
 * reaproveitamos a mesma infraestrutura, apenas com path
 * {@code /api/v1/scrape/indicadores}.</p>
 *
 * <p><b>Configuração:</b> reaproveita {@code gateway.sisab-sidecar.url}
 * (env {@code GATEWAY_SISAB_SIDECAR_URL}). URL ausente curto-circuita
 * antes do round-trip e devolve mensagem específica orientando a
 * ativação do worker — semântica diferente de "5xx do upstream", que
 * usa a mensagem padronizada do brief.</p>
 */
@Slf4j
@Component
public class SisabIndicadoresClient {

    public static final String PROVIDER_NAME = "Sidecar-SISAB-Indicadores";
    private static final String SCRAPE_PATH = "/api/v1/scrape/indicadores";

    /**
     * Mensagem normativa para indisponibilidade do extrator/portal — ditada
     * pelo brief do produto e reusada em fallback do CB e em 5xx upstream
     * para que o ERP consumidor receba contrato textual estável.
     */
    private static final String UPSTREAM_DOWN_MESSAGE =
            "O extrator do SISAB está indisponível ou o portal do DataSUS recusou a conexão. Tente novamente mais tarde.";

    private final RestClient.Builder builder;
    private final String sidecarUrl;

    public SisabIndicadoresClient(RestClient.Builder builder,
                                  @Value("${gateway.sisab-sidecar.url:}") String sidecarUrl) {
        this.builder = builder;
        this.sidecarUrl = sidecarUrl;
    }

    @CircuitBreaker(name = "sisabIndicadoresCB", fallbackMethod = "fallback")
    public IndicadorSinteticoResponse fetch(String codigoIbge, String quadrimestre) {
        if (sidecarUrl == null || sidecarUrl.isBlank()) {
            // Mensagem distinta de UPSTREAM_DOWN_MESSAGE: aqui o problema
            // é configuração local, "tente mais tarde" induziria o operador
            // a esperar pela cura espontânea de algo que precisa de fix.
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Sidecar de indicadores SISAB não configurado. Configure GATEWAY_SISAB_SIDECAR_URL no docker-compose para apontar para http://sisab-sidecar:8000.");
        }

        log.debug("SISAB indicadores fetch ibge={} quadrimestre={}", codigoIbge, quadrimestre);

        try {
            IndicadorSinteticoResponse payload = builder.baseUrl(sidecarUrl).build()
                    .get()
                    .uri(uriBuilder -> uriBuilder.path(SCRAPE_PATH)
                            .queryParam("ibge", codigoIbge)
                            .queryParam("quadrimestre", quadrimestre)
                            .build())
                    .retrieve()
                    .body(IndicadorSinteticoResponse.class);

            if (payload == null) {
                // Sidecar respondeu 200 mas com corpo vazio — trata como
                // indisponibilidade do extrator (DataSUS retornou tabela
                // vazia, parsing falhou silenciosamente etc).
                throw new ResourceUnavailableException(PROVIDER_NAME, UPSTREAM_DOWN_MESSAGE);
            }
            return payload;
        } catch (HttpServerErrorException ex) {
            // Brief: 5xx → mensagem específica padronizada.
            HttpStatusCode status = ex.getStatusCode();
            log.warn("SISAB sidecar retornou HTTP {} para ibge={} quadrimestre={}",
                    status.value(), codigoIbge, quadrimestre);
            throw new ResourceUnavailableException(PROVIDER_NAME, UPSTREAM_DOWN_MESSAGE, ex);
        }
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private IndicadorSinteticoResponse fallback(String codigoIbge, String quadrimestre, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) {
            // Preserva a mensagem específica de configuração ausente.
            throw ru;
        }
        log.warn("SISAB indicadores fallback ibge={} quadrimestre={} cause={}",
                codigoIbge, quadrimestre, cause.toString());
        // CB aberto, timeout, IOException etc → mesma mensagem padronizada.
        throw new ResourceUnavailableException(PROVIDER_NAME, UPSTREAM_DOWN_MESSAGE, cause);
    }
}
