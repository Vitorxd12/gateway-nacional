package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.dto.EquipeEGestorResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client for the e-Gestor APS "Relatório de Pagamento" portal — three-step
 * pure JSON flow ({@code /financiamento/pagamento} menu → componente-pagamento
 * → relatorio-detalhado).
 *
 * <h2>FlareSolverr requirement</h2>
 * <p>{@code relatorioaps-prd.saude.gov.br} sits behind the same F5 BIG-IP
 * profile as DATASUS. Direct API calls are rejected with HTTP 403 even
 * after the most aggressive header / cookie handshake from a Java process.
 * This client therefore <b>requires</b> the FlareSolverr sidecar
 * ({@code gateway.flaresolverr.url}); when it is not configured, every
 * call short-circuits with the standard
 * {@code "Esta rota exige a ativação do sidecar FlareSolverr..."} message.</p>
 */
@Slf4j
@Component
public class EGestorWebClient implements EGestorClientProvider {

    public static final String PROVIDER_NAME = "e-Gestor";
    static final String FLARE_REQUIRED_MESSAGE =
            "Esta rota exige a ativação do sidecar FlareSolverr devido ao WAF governamental.";

    private static final String MENU_PATH = "/financiamento/pagamento";
    private static final String COMPONENTE_PATH = "/financiamento/pagamento/componente-pagamento";
    private static final String DETALHADO_PATH = "/financiamento/pagamento/municipio/relatorio-detalhado";

    private static final String[] INE_FIELDS = {
            "coEquipe", "coEquipeEsb", "co_equipe", "co_equipe_esb",
            "nuIne", "ine", "INE", "ineNormalizado"
    };
    private static final String[] TIPO_EQUIPE_FIELDS = {
            "tpEquipe", "coTipoEquipe", "tipoEquipe", "tp_equipe", "co_tipo_equipe"
    };
    private static final String[] VALOR_FIELDS = {
            "vlPagamento", "vlPagamentoCusteio", "vlTotal", "vlTotalCusteio",
            "vlCusteio", "vlValor", "valor"
    };
    private static final String[] SUSPENSAO_MOTIVO_FIELDS = {
            "dsMotivoSuspensao", "motivoSuspensao", "deMotivoSuspensao",
            "dsValidacao", "validacao", "stSuspensao", "stValidacao"
    };
    private static final String DEFAULT_STATUS = "NÃO SUSPENSO";

    private final String baseUrl;
    private final FlareSolverrInvoker flareSolverr;
    private final ObjectMapper objectMapper;

    public EGestorWebClient(@Value("${gateway.saude.egestor.base-url:https://relatorioaps-prd.saude.gov.br}") String baseUrl,
                            FlareSolverrInvoker flareSolverr,
                            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.flareSolverr = flareSolverr;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "eGestorScraperCB", fallbackMethod = "fallback")
    public List<EquipeEGestorResponse> fetchEquipes(String ibge6, int ano, int mes) {
        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }
        String coUf = ibge6.substring(0, 2);
        String parcela = String.format(Locale.ROOT, "%04d%02d", ano, mes);

        Agrupamento agrupamento = findAgrupamento(coUf, ibge6, parcela);
        if (agrupamento == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "e-Gestor sem agrupamento para IBGE " + ibge6 + " parcela " + parcela + ".");
        }

        // INE+tipo → accumulator. Preserves first-seen iteration order via LinkedHashMap.
        Map<String, EquipeAccumulator> accumByKey = new LinkedHashMap<>();

        for (JsonNode plano : agrupamento.planos()) {
            String coPlanoOrc = textOrNull(plano.get("coSeqPlanoOrcamentario"));
            String dsPlano = textOrNull(plano.get("dsPlanoOrcamentario"));
            if (coPlanoOrc == null) continue;

            JsonNode componentes = fetchComponentes(agrupamento.coProcesso(), coPlanoOrc);
            if (componentes == null || !componentes.isArray()) continue;

            for (JsonNode componente : componentes) {
                ingestComponente(accumByKey, agrupamento, coPlanoOrc, dsPlano, componente, ibge6, parcela);
            }
        }

        if (accumByKey.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "e-Gestor não retornou equipes detalhadas para IBGE " + ibge6 + " parcela " + parcela + ".");
        }

        List<EquipeEGestorResponse> result = new ArrayList<>(accumByKey.size());
        for (EquipeAccumulator acc : accumByKey.values()) {
            result.add(acc.toResponse());
        }
        log.info("e-Gestor resolved {} equipes for IBGE={} parcela={}", result.size(), ibge6, parcela);
        return result;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<EquipeEGestorResponse> fallback(String ibge6, int ano, int mes, Throwable cause) {
        log.warn("e-Gestor fallback triggered for IBGE={} {}/{} cause={}", ibge6, mes, ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "e-Gestor indisponível ou Circuit Breaker aberto.", cause);
    }

    private Agrupamento findAgrupamento(String coUf, String ibge6, String parcela) {
        String url = baseUrl + MENU_PATH
                + "?unidadeGeografica=MUNICIPIO"
                + "&coUf=" + URLEncoder.encode(coUf, StandardCharsets.UTF_8)
                + "&coMunicipio=" + URLEncoder.encode(ibge6, StandardCharsets.UTF_8)
                + "&nuParcelaInicio=" + URLEncoder.encode(parcela, StandardCharsets.UTF_8)
                + "&nuParcelaFim=" + URLEncoder.encode(parcela, StandardCharsets.UTF_8)
                + "&tipoRelatorio=AGRUPADO";

        JsonNode response;
        try {
            response = fetchJson(url);
        } catch (ResourceUnavailableException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "e-Gestor menu inacessível: " + ex.getMessage(), ex);
        }
        if (response == null) return null;
        JsonNode arr = response.get("agrupamentos");
        if (arr == null || !arr.isArray()) {
            return null;
        }
        for (JsonNode item : arr) {
            String nuParcela = textOrNull(item.get("nuParcela"));
            if (parcela.equals(nuParcela)) {
                String coProcesso = textOrNull(item.get("coProcesso"));
                String nuCompCnes = textOrNull(item.get("nuCompCnes"));
                List<JsonNode> planos = new ArrayList<>();
                JsonNode planosNode = item.get("listaPagamentoPlanoOrcamentario");
                if (planosNode != null && planosNode.isArray()) {
                    planosNode.forEach(planos::add);
                }
                if (coProcesso == null) return null;
                return new Agrupamento(coProcesso, nuCompCnes, planos);
            }
        }
        return null;
    }

    private JsonNode fetchComponentes(String coProcesso, String coPlanoOrc) {
        String url = baseUrl + COMPONENTE_PATH
                + "?coProcesso=" + URLEncoder.encode(coProcesso, StandardCharsets.UTF_8)
                + "&coPlanoOrcamentario=" + URLEncoder.encode(coPlanoOrc, StandardCharsets.UTF_8);
        try {
            return fetchJson(url);
        } catch (ResourceUnavailableException ex) {
            log.debug("e-Gestor componente-pagamento failed (coProcesso={}, coPlano={}): {}",
                    coProcesso, coPlanoOrc, ex.getMessage());
            return null;
        }
    }

    private void ingestComponente(Map<String, EquipeAccumulator> accumByKey,
                                  Agrupamento agrupamento,
                                  String coPlanoOrc,
                                  String dsPlanoOrc,
                                  JsonNode componente,
                                  String ibge6,
                                  String parcela) {
        String coComp = textOrNull(componente.get("coComponentePagamento"));
        String coProcPag = textOrNull(componente.get("coProcessoPagamento"));
        String coProcVal = textOrNull(componente.get("coProcessoValidacao"));
        String dsComp = firstNonBlankText(componente, "dsComponentePagamento", "nomeComponente");
        if (coComp == null || coProcPag == null) return;

        String url = baseUrl + DETALHADO_PATH
                + "?nuParcela=" + URLEncoder.encode(parcela, StandardCharsets.UTF_8)
                + "&nuCompCnes=" + URLEncoder.encode(agrupamento.nuCompCnes() != null ? agrupamento.nuCompCnes() : "", StandardCharsets.UTF_8)
                + "&coMunicipio=" + URLEncoder.encode(ibge6, StandardCharsets.UTF_8)
                + "&coComponentePagamento=" + URLEncoder.encode(coComp, StandardCharsets.UTF_8)
                + "&coProcesso=" + URLEncoder.encode(agrupamento.coProcesso(), StandardCharsets.UTF_8)
                + "&coProcessoPagamento=" + URLEncoder.encode(coProcPag, StandardCharsets.UTF_8)
                + "&coProcessoValidacao=" + URLEncoder.encode(coProcVal != null ? coProcVal : "", StandardCharsets.UTF_8)
                + "&coPlanoOrcamentario=" + URLEncoder.encode(coPlanoOrc, StandardCharsets.UTF_8);

        JsonNode detalhe;
        try {
            detalhe = fetchJson(url);
        } catch (ResourceUnavailableException ex) {
            log.debug("e-Gestor relatorio-detalhado failed (comp={}, plano={}): {}",
                    coComp, dsPlanoOrc, ex.getMessage());
            return;
        }
        if (detalhe == null || !detalhe.isObject()) return;

        // Scan top-level array fields for "looks like equipe list" — same heuristic
        // as the AutoAPSFinancias pipeline: first object contains an INE-ish key.
        // Jackson 3: propertyNames() replaces the legacy fieldNames() iterator.
        String fallbackTipo = dsComp != null ? dsComp : dsPlanoOrc;
        for (String field : detalhe.propertyNames()) {
            JsonNode arr = detalhe.get(field);
            if (arr == null || !arr.isArray() || arr.isEmpty()) continue;
            JsonNode first = arr.get(0);
            if (first == null || !first.isObject()) continue;
            if (!looksLikeEquipeNode(first)) continue;

            for (JsonNode equipeNode : arr) {
                ingestEquipe(accumByKey, equipeNode, fallbackTipo);
            }
        }
    }

    private void ingestEquipe(Map<String, EquipeAccumulator> accumByKey,
                              JsonNode equipeNode,
                              String fallbackTipo) {
        try {
            String ine = firstNonBlankText(equipeNode, INE_FIELDS);
            if (ine == null) return;
            String ineCanonical = ine.replaceAll("[^0-9]", "");
            if (ineCanonical.isEmpty()) ineCanonical = ine;

            String tipo = firstNonBlankText(equipeNode, TIPO_EQUIPE_FIELDS);
            if (tipo == null) tipo = fallbackTipo;
            if (tipo == null) tipo = "DESCONHECIDO";

            BigDecimal valor = firstDecimal(equipeNode, VALOR_FIELDS);
            if (valor == null) valor = BigDecimal.ZERO;

            String motivo = firstNonBlankText(equipeNode, SUSPENSAO_MOTIVO_FIELDS);
            String status = (motivo != null && !looksLikeApproval(motivo)) ? motivo.toUpperCase(Locale.ROOT) : DEFAULT_STATUS;

            // Snapshot for the lambda capture — `ineCanonical` and `tipo`
            // were rebound by the null-coalescing if-chains above and are
            // not effectively final.
            final String ineKey = ineCanonical;
            final String tipoKey = tipo;
            String key = ineKey + "|" + tipoKey;
            accumByKey.computeIfAbsent(key, k -> new EquipeAccumulator(ineKey, tipoKey))
                    .accumulate(valor, status);
        } catch (Exception ex) {
            log.debug("e-Gestor skipped malformed equipe: {}", ex.toString());
        }
    }

    /**
     * Routes every JSON GET through FlareSolverr — the same pattern used by
     * {@code CnesWebClient}. The protected upstream still serves JSON;
     * FlareSolverr forwards the body verbatim after solving the WAF challenge.
     */
    private JsonNode fetchJson(String url) {
        FlareSolverrInvoker.FlareResult result = flareSolverr.get(url);
        // jsonBody() unwraps Chrome's <pre> JSON-viewer template that
        // FlareSolverr's headless Chromium injects when the upstream forgets
        // Content-Type: application/json (gov.br does this often).
        String body = result.jsonBody();
        if (body == null || body.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, "e-Gestor devolveu corpo vazio para " + url);
        }
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "e-Gestor devolveu corpo não-JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * Heuristic: an object "looks like" an equipe entry when at least one
     * of its keys matches the known INE aliases. Fast O(k) scan, k ≤ 8.
     */
    private static boolean looksLikeEquipeNode(JsonNode first) {
        for (String field : INE_FIELDS) {
            if (first.has(field)) return true;
        }
        return false;
    }

    private static boolean looksLikeApproval(String value) {
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("APROVAD") || upper.equals("OK") || upper.equals("VALIDO") || upper.equals("VÁLIDO");
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

    private static BigDecimal firstDecimal(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child == null || child.isNull()) continue;
            if (child.isNumber()) return child.decimalValue();
            String text = child.asText("").trim();
            if (text.isEmpty()) continue;
            try {
                return new BigDecimal(text.replace(",", "."));
            } catch (NumberFormatException ignored) {
                // try next alias
            }
        }
        return null;
    }

    private record Agrupamento(String coProcesso, String nuCompCnes, List<JsonNode> planos) {
    }

    /**
     * Per-equipe aggregator — sums valores across components and keeps the
     * first non-default suspensão status seen for that team.
     */
    private static final class EquipeAccumulator {
        private final String ine;
        private final String tipoEquipe;
        private BigDecimal valorCusteio = BigDecimal.ZERO;
        private String statusSuspensao = DEFAULT_STATUS;

        EquipeAccumulator(String ine, String tipoEquipe) {
            this.ine = ine;
            this.tipoEquipe = tipoEquipe;
        }

        void accumulate(BigDecimal valor, String status) {
            if (valor != null) {
                valorCusteio = valorCusteio.add(valor);
            }
            // First non-default status wins — once a team is flagged suspended in
            // any component, that motive is preserved across the aggregate.
            if (DEFAULT_STATUS.equals(statusSuspensao) && status != null && !DEFAULT_STATUS.equals(status)) {
                statusSuspensao = status;
            }
        }

        EquipeEGestorResponse toResponse() {
            return new EquipeEGestorResponse(ine, tipoEquipe, valorCusteio, statusSuspensao);
        }
    }
}
