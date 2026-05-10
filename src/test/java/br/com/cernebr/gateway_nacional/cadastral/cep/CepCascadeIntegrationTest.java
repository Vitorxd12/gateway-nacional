package br.com.cernebr.gateway_nacional.cadastral.cep;

import br.com.cernebr.gateway_nacional.config.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integração black-box do hedge paralelo de CEP. Cada teste stuba uma
 * combinação diferente de respostas dos providers no WireMock compartilhado
 * e valida o contrato externo via MockMvc.
 *
 * <p>Sob hedge, todos os providers são disparados em paralelo. Os providers
 * que não estão stubados respondem 404 default do WireMock e são contados
 * como falha — só providers explicitamente stubados com 2xx podem vencer.</p>
 */
class CepCascadeIntegrationTest extends AbstractIntegrationTest {

    private static final String VALID_CEP = "01001000";

    private static final String VIACEP_PATH = "/ws/" + VALID_CEP + "/json/";
    private static final String BRASILAPI_PATH = "/api/cep/v1/" + VALID_CEP;
    private static final String AWESOMEAPI_PATH = "/json/" + VALID_CEP;

    private static final String VIACEP_OK_BODY = """
            {
              "cep": "01001-000",
              "logradouro": "Praça da Sé",
              "complemento": "lado ímpar",
              "bairro": "Sé",
              "localidade": "São Paulo",
              "uf": "SP",
              "ibge": "3550308"
            }
            """;

    private static final String BRASILAPI_OK_BODY = """
            {
              "cep": "01001-000",
              "state": "SP",
              "city": "São Paulo",
              "neighborhood": "Sé",
              "street": "Praça da Sé",
              "service": "open-cep"
            }
            """;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Should return 200 with ViaCEP payload when ViaCEP is the only healthy provider")
    void shouldReturnCepFromViaCepWhenAvailable() throws Exception {
        // Given — só ViaCEP retorna 200; demais caem no 404 default do WireMock
        WIREMOCK.stubFor(get(urlPathEqualTo(VIACEP_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VIACEP_OK_BODY)));

        // When / Then — sob hedge, ViaCEP é o único success → vence
        mockMvc.perform(get("/api/v1/cep/{cep}", VALID_CEP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cep").value("01001-000"))
                .andExpect(jsonPath("$.logradouro").value("Praça da Sé"))
                .andExpect(jsonPath("$.uf").value("SP"))
                .andExpect(jsonPath("$.localidade").value("São Paulo"))
                .andExpect(jsonPath("$.ibge").value("3550308"));
    }

    @Test
    @DisplayName("Should win with BrasilAPI and back-fill IBGE when ViaCEP returns 500")
    void shouldCascadeToBrasilApiWhenViaCepFails() throws Exception {
        // Given — ViaCEP 500, BrasilAPI 200 (sem IBGE); AwesomeAPI no 404 default
        WIREMOCK.stubFor(get(urlPathEqualTo(VIACEP_PATH))
                .willReturn(aResponse().withStatus(500)));

        WIREMOCK.stubFor(get(urlPathEqualTo(BRASILAPI_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BRASILAPI_OK_BODY)));

        // When / Then — BrasilAPI vence o hedge; IbgeEnrichmentService backfilla
        // o código IBGE (São Paulo / SP -> 3550308) a partir do registry local.
        mockMvc.perform(get("/api/v1/cep/{cep}", VALID_CEP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cep").value("01001-000"))
                .andExpect(jsonPath("$.uf").value("SP"))
                .andExpect(jsonPath("$.localidade").value("São Paulo"))
                .andExpect(jsonPath("$.ibge").value("3550308"));
    }

    @Test
    @DisplayName("Should return 503 ProblemDetail when every CEP provider fails")
    void shouldReturn503WhenAllProvidersFail() throws Exception {
        // Given — todos os providers respondem 500
        WIREMOCK.stubFor(get(urlPathEqualTo(VIACEP_PATH))
                .willReturn(aResponse().withStatus(500)));
        WIREMOCK.stubFor(get(urlPathEqualTo(BRASILAPI_PATH))
                .willReturn(aResponse().withStatus(500)));
        WIREMOCK.stubFor(get(urlPathEqualTo(AWESOMEAPI_PATH))
                .willReturn(aResponse().withStatus(500)));

        // When / Then — hedge esgota todos os candidatos → ResourceUnavailableException
        // → GlobalExceptionHandler emite RFC 7807 ProblemDetail com status 503
        mockMvc.perform(get("/api/v1/cep/{cep}", VALID_CEP))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.type").value("https://api.gateway-nacional.com.br/errors/resource-unavailable"))
                .andExpect(jsonPath("$.provider").value("cep"));
    }

    @Test
    @DisplayName("Should increment gateway.hedge.winner counter for the resolved provider")
    void shouldIncrementHedgeWinnerCounter() throws Exception {
        // Given — ViaCEP é o único success
        WIREMOCK.stubFor(get(urlPathEqualTo(VIACEP_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VIACEP_OK_BODY)));

        double before = winnerCount("viacep");

        // When — chama o endpoint uma vez (cache foi limpo no @BeforeEach)
        mockMvc.perform(get("/api/v1/cep/{cep}", VALID_CEP))
                .andExpect(status().isOk());

        // Then — counter "viacep" subiu exatamente 1
        double after = winnerCount("viacep");
        assertThat(after - before)
                .as("gateway.hedge.winner{domain=cep, provider=viacep} deve ter incrementado 1")
                .isEqualTo(1.0);
    }

    private double winnerCount(String providerTag) {
        return meterRegistry.find("gateway.hedge.winner")
                .tag("domain", "cep")
                .tag("provider", providerTag)
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();
    }
}
