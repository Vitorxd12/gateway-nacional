package br.com.cernebr.gateway_nacional.financeiro.taxas.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.taxas.dto.TaxaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Tertiary rate provider — HG Brasil (https://api.hgbrasil.com).
 * Endpoint: {@code /finance/taxes?key={key}}.
 *
 * <p><b>Coverage limitation:</b> the {@code /finance/taxes} endpoint only
 * exposes Selic and CDI. IPCA is not available — when the cascade reaches
 * this provider asking for IPCA, the client throws
 * {@link ResourceUnavailableException}. In practice this only happens if
 * both BrasilAPI and BCB SGS are simultaneously unavailable for IPCA.</p>
 *
 * <p>The default API key {@code "development"} is HG Brasil's free tier
 * placeholder — overrideable via
 * {@code gateway.taxas.hg-brasil.api-key} for production credentials.</p>
 */
@Slf4j
@Component
public class HgBrasilTaxasClient implements TaxaClientProvider {

    public static final String PROVIDER_NAME = "HG-Brasil";

    private final RestClient restClient;
    private final String apiKey;

    public HgBrasilTaxasClient(RestClient.Builder builder,
                               @Value("${gateway.taxas.hg-brasil.base-url:https://api.hgbrasil.com}") String baseUrl,
                               @Value("${gateway.taxas.hg-brasil.api-key:development}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    @CircuitBreaker(name = "hgBrasilCB", fallbackMethod = "fallback")
    public TaxaResponse fetch(String sigla) {
        String canonical = sigla.toUpperCase(Locale.ROOT);

        HgBrasilEnvelope envelope = restClient.get()
                .uri("/finance/taxes?key={key}", apiKey)
                .retrieve()
                .body(HgBrasilEnvelope.class);

        if (envelope == null || envelope.results() == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "HG Brasil retornou resposta vazia.");
        }

        HgBrasilResults results = envelope.results();
        BigDecimal valor = switch (canonical) {
            case "CDI"   -> results.cdi();
            case "SELIC" -> results.selic();
            default      -> null;
        };

        if (valor == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "HG Brasil não fornece a taxa solicitada: " + canonical);
        }

        LocalDate referenceDate = (results.date() != null && !results.date().isBlank())
                ? LocalDate.parse(results.date())
                : LocalDate.now();

        return new TaxaResponse(canonical, valor, referenceDate);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private TaxaResponse fallback(String sigla, Throwable cause) {
        log.warn("HG Brasil fallback triggered for sigla={} cause={}", sigla, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "HG Brasil indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HgBrasilEnvelope(HgBrasilResults results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HgBrasilResults(String date, BigDecimal cdi, BigDecimal selic) {
    }
}
