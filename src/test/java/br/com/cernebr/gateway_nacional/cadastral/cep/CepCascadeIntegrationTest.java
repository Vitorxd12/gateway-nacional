package br.com.cernebr.gateway_nacional.cadastral.cep;

import br.com.cernebr.gateway_nacional.config.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Black-box integration test for the CEP cascade fallback. Each test stubs
 * a different combination of provider responses on the shared WireMock and
 * asserts the externally observable contract via MockMvc.
 */
class CepCascadeIntegrationTest extends AbstractIntegrationTest {

    private static final String VALID_CEP = "01001000";

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

    @Test
    @DisplayName("Should return 200 with ViaCEP payload when ViaCEP is healthy")
    void shouldReturnCepFromViaCepWhenAvailable() throws Exception {
        // Given — ViaCEP responds 200 with a valid payload
        WIREMOCK.stubFor(get(urlPathEqualTo("/ws/" + VALID_CEP + "/json/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VIACEP_OK_BODY)));

        // When / Then — gateway returns 200 with the unified payload
        mockMvc.perform(get("/api/v1/cep/{cep}", VALID_CEP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cep").value("01001-000"))
                .andExpect(jsonPath("$.logradouro").value("Praça da Sé"))
                .andExpect(jsonPath("$.uf").value("SP"))
                .andExpect(jsonPath("$.localidade").value("São Paulo"))
                .andExpect(jsonPath("$.ibge").value("3550308"));
    }

    @Test
    @DisplayName("Should cascade to BrasilAPI and back-fill IBGE when ViaCEP returns 500")
    void shouldCascadeToBrasilApiWhenViaCepFails() throws Exception {
        // Given — ViaCEP fails with 500 and BrasilAPI succeeds (with no IBGE field)
        WIREMOCK.stubFor(get(urlPathEqualTo("/ws/" + VALID_CEP + "/json/"))
                .willReturn(aResponse().withStatus(500)));

        WIREMOCK.stubFor(get(urlPathEqualTo("/api/cep/v1/" + VALID_CEP))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(BRASILAPI_OK_BODY)));

        // When / Then — gateway returns 200 with BrasilAPI data, and
        // IbgeEnrichmentService back-fills the missing IBGE code from the
        // bundled in-memory registry (São Paulo / SP -> 3550308).
        mockMvc.perform(get("/api/v1/cep/{cep}", VALID_CEP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cep").value("01001-000"))
                .andExpect(jsonPath("$.uf").value("SP"))
                .andExpect(jsonPath("$.localidade").value("São Paulo"))
                .andExpect(jsonPath("$.ibge").value("3550308"));
    }
}
