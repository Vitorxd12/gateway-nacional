package br.com.cernebr.gateway_nacional.juridico.cnd.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.juridico.cnd.dto.CndResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Cliente de CND que delega a emissão para o sidecar Python/Selenium
 * compartilhado com o módulo SISAB.
 *
 * <p><b>Por que sidecar:</b> os portais oficiais (Receita Federal, FGTS/Caixa,
 * TST) usam cascatas de formulários JSF/Mojarra com cookies pesados, captcha
 * eventual e ViewState que muda a cada navegação. Reproduzir esse fluxo em
 * HTTP puro com {@link RestClient} é frágil — a cada atualização do portal
 * a integração quebra silenciosamente. O sidecar Python (Selenium + Chromium
 * headless) abstrai a navegação e devolve um JSON estável; concentrar toda a
 * fragilidade num único componente reaproveita o que já foi pago em
 * infraestrutura para o SISAB.</p>
 *
 * <p><b>Configuração:</b> reaproveita {@code gateway.sisab-sidecar.url}
 * (env {@code GATEWAY_SISAB_SIDECAR_URL}). Quando ausente, o cliente
 * curto-circuita antes do round-trip e levanta
 * {@link ResourceUnavailableException} com mensagem explícita orientando
 * a ativação do worker — mesmo padrão do {@code FlareSolverrInvoker}.</p>
 */
@Slf4j
@Component
public class CndSidecarClient {

    public static final String PROVIDER_NAME = "Sidecar-CND";
    private static final String SCRAPE_PATH = "/api/v1/scrape/cnd";

    private final RestClient.Builder builder;
    private final String sidecarUrl;

    public CndSidecarClient(RestClient.Builder builder,
                            @Value("${gateway.sisab-sidecar.url:}") String sidecarUrl) {
        this.builder = builder;
        this.sidecarUrl = sidecarUrl;
    }

    @CircuitBreaker(name = "cndSidecarCB", fallbackMethod = "fallback")
    public CndResponse emitir(String cnpj, String tipo) {
        if (sidecarUrl == null || sidecarUrl.isBlank()) {
            // Mensagem espelha o tom do módulo de saúde (SISAB) — operador
            // identifica imediatamente onde mexer (env do worker).
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Esta rota exige a ativação do sidecar Python de scraping. Configure GATEWAY_SISAB_SIDECAR_URL no docker-compose para apontar para http://sisab-sidecar:8000.");
        }

        log.debug("CND sidecar request cnpj={} tipo={}", cnpj, tipo);

        String canonicalTipo = tipo.toUpperCase(Locale.ROOT);
        SidecarResponse payload = builder.baseUrl(sidecarUrl).build()
                .post()
                .uri(SCRAPE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cnpj", cnpj, "tipo", canonicalTipo))
                .retrieve()
                .body(SidecarResponse.class);

        if (payload == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Sidecar de CND retornou resposta vazia para cnpj=" + cnpj + " tipo=" + canonicalTipo);
        }
        return payload.toResponse(cnpj, canonicalTipo);
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CndResponse fallback(String cnpj, String tipo, Throwable cause) {
        if (cause instanceof ResourceUnavailableException ru) {
            throw ru;
        }
        log.warn("CND sidecar fallback cnpj={} tipo={} cause={}", cnpj, tipo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Sidecar de CND indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SidecarResponse(
            String status,
            LocalDate dataEmissao,
            LocalDate validade,
            String codigoControle
    ) {
        CndResponse toResponse(String cnpj, String tipo) {
            return new CndResponse(cnpj, tipo, status, dataEmissao, validade, codigoControle);
        }
    }
}
