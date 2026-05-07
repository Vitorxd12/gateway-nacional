package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.dto.ProfissionalCnesResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the CNES (Cadastro Nacional de Estabelecimentos de Saúde) APS
 * portal — DATASUS internal JSON services.
 *
 * <p>Two-step pure JSON flow:
 * <ol>
 *   <li>{@code /services/estabelecimentos-equipes/{ibge}{cnes}} — lists every
 *       team registered in the establishment, with {@code coEquipe} (INE) and
 *       {@code coArea} (a per-team area code that the next call requires);</li>
 *   <li>{@code /services/estabelecimentos-equipes/profissionais/{ibge}{cnes}}
 *       with query params {@code coMun}, {@code coArea}, {@code coEquipe} —
 *       returns the professionals bound to that specific team.</li>
 * </ol>
 *
 * <h2>FlareSolverr requirement</h2>
 * <p>gov.br fronts every DATASUS service with an F5 BIG-IP WAF that blocks
 * direct API calls without a browser-grade session. After validating in
 * production that no realistic header/cookie handshake from a Java process
 * gets through, this client now <b>requires</b> the FlareSolverr sidecar
 * ({@code gateway.flaresolverr.url}) to function. When the sidecar is not
 * configured, every call short-circuits with
 * {@code "Esta rota exige a ativação do sidecar FlareSolverr devido ao WAF
 * governamental."} — fast 503 instead of a slow timeout-then-403.</p>
 *
 * <p><b>Composite key requirement:</b> the upstream is keyed by
 * {@code {ibge}{cnes}} — a 7-digit CNES code is not unique across Brazil,
 * so the controller propagates the IBGE here as a query param.</p>
 */
@Slf4j
@Component
public class CnesWebClient implements CnesClientProvider {

    public static final String PROVIDER_NAME = "CNES";
    static final String FLARE_REQUIRED_MESSAGE =
            "Esta rota exige a ativação do sidecar FlareSolverr devido ao WAF governamental.";

    private static final String EQUIPES_PATH = "/services/estabelecimentos-equipes/";
    private static final String PROFISSIONAIS_PATH = "/services/estabelecimentos-equipes/profissionais/";

    private static final String[] NOME_FIELDS = {
            "noProfissional", "nomeProfissional", "noProf", "nome"
    };
    private static final String[] CNS_FIELDS = {
            "nuCns", "cns", "nuCnsProfissional", "noCns", "numeroCns"
    };
    private static final String[] CBO_FIELDS = {
            "cbo", "coCbo", "nuCbo"
    };
    private static final String[] DATA_ENTRADA_FIELDS = {
            "dtEntrada", "dataEntrada", "dtInicioAtividade", "dtAdmissao", "dataAdmissao"
    };
    private static final String[] CH_AMB_FIELDS = {"chAmb", "cargaHorariaAmbulatorial", "ch_amb"};
    private static final String[] CH_OUTROS_FIELDS = {"chOutros", "cargaHorariaOutros", "ch_outros"};

    private final String baseUrl;
    private final FlareSolverrInvoker flareSolverr;
    private final ObjectMapper objectMapper;

    public CnesWebClient(@Value("${gateway.saude.cnes.base-url:https://cnes.datasus.gov.br}") String baseUrl,
                         FlareSolverrInvoker flareSolverr,
                         ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.flareSolverr = flareSolverr;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "cnesScraperCB", fallbackMethod = "fallback")
    public List<ProfissionalCnesResponse> fetchProfissionais(String cnesBase, String ibge6) {
        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }
        String idComposto = ibge6 + cnesBase;

        JsonNode equipes = fetchJson(baseUrl + EQUIPES_PATH + idComposto);
        if (equipes == null || !equipes.isArray() || equipes.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CNES não encontrou equipes para o estabelecimento " + idComposto + ".");
        }

        List<ProfissionalCnesResponse> profissionais = new ArrayList<>();
        for (JsonNode equipe : equipes) {
            String coEquipe = textOrNull(equipe.get("coEquipe"));
            if (coEquipe == null) continue;
            String coArea = firstNonBlankText(equipe, "coArea", "co_area");
            if (coArea == null) coArea = "0001";

            JsonNode profs = fetchProfissionaisForEquipe(idComposto, ibge6, coArea, coEquipe);
            if (profs == null || !profs.isArray()) continue;

            String ineCanonical = canonicalIne(coEquipe);
            for (JsonNode prof : profs) {
                try {
                    ProfissionalCnesResponse mapped = mapProfissional(prof, cnesBase, ineCanonical);
                    if (mapped != null) {
                        profissionais.add(mapped);
                    }
                } catch (Exception ex) {
                    log.debug("CNES skipped malformed profissional: {}", ex.toString());
                }
            }
        }

        if (profissionais.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CNES retornou equipes mas nenhum profissional pôde ser extraído para " + idComposto + ".");
        }
        log.info("CNES resolved {} profissionais for IBGE+CNES={}", profissionais.size(), idComposto);
        return profissionais;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<ProfissionalCnesResponse> fallback(String cnesBase, String ibge6, Throwable cause) {
        log.warn("CNES fallback triggered for IBGE={} CNES={} cause={}", ibge6, cnesBase, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "CNES indisponível ou Circuit Breaker aberto.", cause);
    }

    private JsonNode fetchProfissionaisForEquipe(String idComposto, String ibge6,
                                                 String coArea, String coEquipe) {
        String coEquipeZeroPad = padLeftZeros(coEquipe, 10);
        String url = baseUrl + PROFISSIONAIS_PATH + idComposto
                + "?coMun=" + URLEncoder.encode(ibge6, StandardCharsets.UTF_8)
                + "&coArea=" + URLEncoder.encode(coArea, StandardCharsets.UTF_8)
                + "&coEquipe=" + URLEncoder.encode(coEquipeZeroPad, StandardCharsets.UTF_8);
        try {
            return fetchJson(url);
        } catch (ResourceUnavailableException ex) {
            log.debug("CNES profissionais failed (id={}, equipe={}): {}",
                    idComposto, coEquipe, ex.getMessage());
            return null;
        }
    }

    /**
     * Routes every JSON GET through FlareSolverr. The protected upstream
     * still serves JSON; FlareSolverr just forwards the body verbatim after
     * solving the WAF challenge, so we parse with Jackson 3 as usual.
     */
    private JsonNode fetchJson(String url) {
        FlareSolverrInvoker.FlareResult result = flareSolverr.get(url);
        // jsonBody() unwraps Chrome's <pre> JSON-viewer template that
        // FlareSolverr's headless Chromium injects when the upstream forgets
        // Content-Type: application/json (gov.br does this often).
        String body = result.jsonBody();
        if (body == null || body.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CNES devolveu corpo vazio para " + url);
        }
        // DATASUS occasionally answers a HTML page with "Your connection was
        // refused" on a 200 — internal backend hiccup. Translate it to a
        // human-readable RUE before the JSON parser stumbles on the <html>.
        if (body.contains("connection was refused") || body.contains("conexão foi recusada")) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "DATASUS recusou a conexão interna (HTML 'connection was refused'). Estabelecimento sem APS ou backend instável.");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CNES devolveu corpo não-JSON (talvez HTML de challenge): " + ex.getMessage(), ex);
        }
    }

    private ProfissionalCnesResponse mapProfissional(JsonNode prof, String cnesBase, String ineCanonical) {
        if (prof == null || !prof.isObject()) {
            return null;
        }
        String nome = firstNonBlankText(prof, NOME_FIELDS);
        if (nome == null) return null;

        String cns = firstNonBlankText(prof, CNS_FIELDS);
        String cbo = firstNonBlankText(prof, CBO_FIELDS);
        String dataEntrada = firstNonBlankText(prof, DATA_ENTRADA_FIELDS);

        int ch = sumNumericFields(prof, CH_AMB_FIELDS) + sumNumericFields(prof, CH_OUTROS_FIELDS);

        return new ProfissionalCnesResponse(
                cnesBase,
                ineCanonical,
                nome,
                cns != null ? cns : "",
                cbo != null ? cbo : "",
                ch,
                dataEntrada != null ? dataEntrada : ""
        );
    }

    private static String canonicalIne(String coEquipe) {
        String digits = coEquipe.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? coEquipe : padLeftZeros(digits, 10);
    }

    private static String padLeftZeros(String value, int width) {
        if (value.length() >= width) return value;
        StringBuilder sb = new StringBuilder(width);
        for (int i = value.length(); i < width; i++) sb.append('0');
        sb.append(value);
        return sb.toString();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String txt = node.asText("").trim();
        return txt.isEmpty() ? null : txt;
    }

    private static String firstNonBlankText(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            String value = textOrNull(node.get(field));
            if (value != null) return value;
        }
        return null;
    }

    /**
     * Sums every numeric value found across the alias list. Returns 0 when
     * none match — chAmb + chOutros are routinely null/missing for some
     * professional types and that is not an error.
     */
    private static int sumNumericFields(JsonNode node, String... fields) {
        if (node == null) return 0;
        int sum = 0;
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child == null || child.isNull()) continue;
            if (child.isNumber()) {
                sum += child.intValue();
                continue;
            }
            String text = child.asText("").trim();
            if (text.isEmpty()) continue;
            try {
                sum += (int) Double.parseDouble(text.replace(",", "."));
            } catch (NumberFormatException ignored) {
                // ignore alias and try next
            }
        }
        return sum;
    }
}
