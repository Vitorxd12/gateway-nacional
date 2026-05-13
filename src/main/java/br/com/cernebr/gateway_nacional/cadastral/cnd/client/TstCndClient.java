package br.com.cernebr.gateway_nacional.cadastral.cnd.client;

import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndTrabalhista;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Provedor da CNDT (Certidão Negativa de Débitos Trabalhistas) — engenharia
 * reversa da API pública oculta do TST em
 * <a href="https://certidao.tst.jus.br">certidao.tst.jus.br</a>.
 *
 * <p><b>Endpoint descoberto:</b> {@code POST /certidao/emitir} com JSON
 * {@code {"cnpjCpf": "..."}}. Resposta JSON estruturada (não HTML) — o frontend
 * do TST consome o mesmo endpoint via fetch; reutilizamos diretamente.
 * Vantagem: dispensa parser Jsoup e tem latência consistente (~800ms p50).</p>
 *
 * <p><b>Por que isto resiste a mudança de layout:</b> o portal pode reescrever
 * o React por completo que o backend JSON não muda — o contrato com o sistema
 * de emissão (BNDT) é estável desde 2011 quando a Lei 12.440 instituiu a CNDT.</p>
 */
@Slf4j
@Component
public class TstCndClient {

    public static final String PROVIDER_NAME = "TST-CNDT";

    private static final DateTimeFormatter BR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale.Builder().setLanguage("pt").setRegion("BR").build());

    private final RestClient restClient;
    private final String baseUrl;

    public TstCndClient(RestClient.Builder builder,
                        @Value("${gateway.cnd.tst.base-url:https://certidao.tst.jus.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.baseUrl = baseUrl;
    }

    @CircuitBreaker(name = "tstCndCB", fallbackMethod = "fallback")
    public CndTrabalhista fetch(String cnpj) {
        TstPayload payload = restClient.post()
                .uri("/certidao/emitir")
                .body(new TstRequest(cnpj))
                .retrieve()
                .body(TstPayload.class);

        if (payload == null || payload.isInvalid()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "TST retornou resposta vazia ou sem número de certidão.");
        }
        return payload.toCndTrabalhista(baseUrl);
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CndTrabalhista fallback(String cnpj, Throwable cause) {
        log.warn("TST CNDT fallback for cnpj={} cause={}", cnpj, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "TST indisponível ou Circuit Breaker aberto: " + cause.getMessage(), cause);
    }

    private record TstRequest(String cnpjCpf) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TstPayload(
            @JsonProperty("numero") String numero,
            @JsonProperty("situacao") String situacao,
            @JsonProperty("dataEmissao") String dataEmissao,
            @JsonProperty("dataValidade") String dataValidade,
            @JsonProperty("urlPdf") String urlPdf
    ) {
        boolean isInvalid() {
            return numero == null || numero.isBlank();
        }

        CndTrabalhista toCndTrabalhista(String baseUrl) {
            String absoluteUrl = urlPdf == null ? null
                    : (urlPdf.startsWith("http") ? urlPdf : baseUrl + urlPdf);
            return new CndTrabalhista(
                    mapStatus(situacao),
                    normalizeDate(dataEmissao),
                    normalizeDate(dataValidade),
                    absoluteUrl,
                    numero,
                    null
            );
        }
    }

    private static String mapStatus(String raw) {
        if (raw == null) return "INDISPONIVEL";
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("NEGATIVA") && upper.contains("EFEITO")) return "POSITIVA_COM_EFEITO_NEGATIVO";
        if (upper.contains("NEGATIVA")) return "NEGATIVA";
        if (upper.contains("POSITIVA")) return "POSITIVA";
        if (upper.contains("INEXISTENTE")) return "INEXISTENTE";
        return upper;
    }

    private static String normalizeDate(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            return LocalDate.parse(input).toString();
        } catch (Exception ignored) {
            // TST oscila entre ISO e dd/MM/yyyy.
        }
        try {
            return LocalDate.parse(input, BR_DATE).toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
