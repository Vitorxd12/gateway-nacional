package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.dto.RepasseFnsResponse;
import tools.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Client for the FNS (Fundo Nacional de Saúde) "Consulta Detalhada" portal.
 *
 * <p>Despite the portal being a SPA, the data is served as JSON by two
 * undocumented endpoints:
 * <ul>
 *   <li>{@code /recursos/consulta-detalhada/entidades} — returns the
 *       entities (CNPJ/UG) registered for a given municipio in the year;</li>
 *   <li>{@code /recursos/consulta-detalhada/detalhe-acao} — returns the
 *       actual repasses for a given {ano, mes, municipio, UG, tipoConsulta}.</li>
 * </ul>
 *
 * <p>The first call discovers the canonical UG/CNPJ; the second pulls the
 * line items. When the canonical CNPJ returns empty (frequent — many cities
 * have repasses parked under a Fundo Municipal de Saúde with a different
 * CNPJ), we cascade through the other entities returned by call 1 and also
 * flip {@code tipoConsulta} between 1 and 2. This mirrors the proven
 * fallback path of the AutoAPSFinancias pipeline.</p>
 *
 * <p><b>Anti-bot caveat:</b> the original AutoAPSFinancias pipeline warms
 * cookies via headless Chrome before hitting these endpoints. We attempt the
 * call directly with browser-like headers — when gov.br rejects it, the CB
 * trips, the response is a clean 503 with the upstream error surfaced.
 * Document this as a known constraint of the self-hosted gateway: shipping
 * Selenium would be a 200MB+ dependency for an edge case better solved by
 * an external warmup sidecar when (if) needed.</p>
 */
@Slf4j
@Component
public class FnsWebClient implements FnsClientProvider {

    public static final String PROVIDER_NAME = "FNS";

    private static final String ENTIDADES_PATH = "/recursos/consulta-detalhada/entidades";
    private static final String DETALHE_PATH = "/recursos/consulta-detalhada/detalhe-acao";
    private static final String[] TIPO_CONSULTA_ORDER = {"2", "1"};
    private static final int MAX_FALLBACK_ENTITIES = 10;

    private final RestClient restClient;
    private final String baseUrl;

    public FnsWebClient(RestClient.Builder builder,
                        @Value("${gateway.saude.fns.base-url:https://consultafns.saude.gov.br}") String baseUrl,
                        @Value("${gateway.saude.fns.user-agent:Mozilla/5.0 (compatible; GatewayNacional/1.0)}") String userAgent) {
        this.baseUrl = baseUrl;
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Referer", baseUrl + "/")
                .defaultHeader("X-Requested-With", "XMLHttpRequest")
                .build();
    }

    @Override
    @CircuitBreaker(name = "fnsScraperCB", fallbackMethod = "fallback")
    public List<RepasseFnsResponse> fetchRepasses(String ibge6, int ano, int mes, String competencia) {
        String uf = IbgeUfLookup.ufFromIbge(ibge6);
        if (uf == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Código IBGE inválido para derivação de UF: " + ibge6);
        }

        Set<String> cnpjsToTry = discoverEntityCnpjs(ibge6, ano, uf);
        if (cnpjsToTry.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FNS retornou lista de entidades vazia para IBGE " + ibge6 + ". Município sem repasses ou portal bloqueando.");
        }

        for (String tipoConsulta : TIPO_CONSULTA_ORDER) {
            for (String cnpj : cnpjsToTry) {
                List<RepasseFnsResponse> repasses = tryFetchRepasses(ibge6, ano, mes, competencia, uf, cnpj, tipoConsulta);
                if (!repasses.isEmpty()) {
                    log.info("FNS resolved {} repasses for IBGE={} {}/{} via cnpj={} tipoConsulta={}",
                            repasses.size(), ibge6, mes, ano, cnpj, tipoConsulta);
                    return repasses;
                }
            }
        }

        throw new ResourceUnavailableException(PROVIDER_NAME,
                "FNS não retornou repasses para IBGE " + ibge6 + " competência " + competencia
                        + " após tentativas em " + cnpjsToTry.size() + " UG(s).");
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<RepasseFnsResponse> fallback(String ibge6, int ano, int mes, String competencia, Throwable cause) {
        log.warn("FNS fallback triggered for IBGE={} {}/{} cause={}", ibge6, mes, ano, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "FNS indisponível ou Circuit Breaker aberto.", cause);
    }

    private Set<String> discoverEntityCnpjs(String ibge6, int ano, String uf) {
        JsonNode response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(ENTIDADES_PATH)
                            .queryParam("ano", ano)
                            .queryParam("count", 100)
                            .queryParam("estado", uf)
                            .queryParam("municipio", ibge6)
                            .queryParam("page", 1)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FNS endpoint de entidades inacessível: " + ex.getClass().getSimpleName(), ex);
        }

        Set<String> cnpjs = new LinkedHashSet<>();
        JsonNode dadosNode = extractDadosArray(response);
        if (dadosNode == null || !dadosNode.isArray()) {
            return cnpjs;
        }
        int taken = 0;
        for (JsonNode entidade : dadosNode) {
            if (taken >= MAX_FALLBACK_ENTITIES) break;
            String cnpj = firstNonBlankText(entidade, "cpfCnpj", "cnpj");
            if (cnpj != null) {
                cnpjs.add(cnpj);
                taken++;
            }
        }
        return cnpjs;
    }

    private List<RepasseFnsResponse> tryFetchRepasses(String ibge6, int ano, int mes, String competencia,
                                                      String uf, String cnpj, String tipoConsulta) {
        JsonNode response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(DETALHE_PATH)
                            .queryParam("ano", ano)
                            .queryParam("count", 100)
                            .queryParam("cpfCnpjUg", cnpj)
                            .queryParam("estado", uf)
                            .queryParam("mes", mes)
                            .queryParam("municipio", ibge6)
                            .queryParam("page", 1)
                            .queryParam("tipoConsulta", tipoConsulta)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            log.debug("FNS detalhe-acao failed (cnpj={}, tipo={}): {}", cnpj, tipoConsulta, ex.getMessage());
            return List.of();
        }

        JsonNode dadosNode = extractDadosArray(response);
        if (dadosNode == null || !dadosNode.isArray() || dadosNode.isEmpty()) {
            return List.of();
        }

        List<RepasseFnsResponse> repasses = new ArrayList<>();
        for (JsonNode item : dadosNode) {
            try {
                RepasseFnsResponse repasse = mapRepasse(item, ibge6, competencia);
                if (repasse != null) {
                    repasses.add(repasse);
                }
            } catch (Exception ex) {
                log.debug("FNS skipped malformed repasse item: {}", ex.toString());
            }
        }
        return repasses;
    }

    /**
     * Defensive ACL — picks {@code grupoAcao.nome} as bloco when present,
     * falls back to {@code descricao}. Reads {@code valorLiquido} as text
     * (FNS sometimes returns numbers, sometimes strings) and parses to
     * {@link BigDecimal}.
     */
    private RepasseFnsResponse mapRepasse(JsonNode item, String ibge6, String competencia) {
        if (item == null || !item.isObject()) {
            return null;
        }
        String bloco = null;
        JsonNode grupo = item.get("grupoAcao");
        if (grupo != null && grupo.isObject()) {
            bloco = textOrNull(grupo.get("nome"));
        }
        if (bloco == null) {
            bloco = firstNonBlankText(item, "descricao", "nome", "acao");
        }

        BigDecimal valor = parseDecimalNode(item.get("valorLiquido"));
        if (valor == null) {
            valor = parseDecimalNode(item.get("valor"));
        }
        if (valor == null || bloco == null) {
            return null;
        }
        return new RepasseFnsResponse(ibge6, competencia, bloco, valor);
    }

    /**
     * FNS uses two payload shapes: {@code {"resultado": {"dados": [...]}}}
     * and {@code {"dados": [...]}}. This helper hides the distinction.
     */
    private JsonNode extractDadosArray(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode resultado = root.get("resultado");
        if (resultado != null && resultado.isObject()) {
            JsonNode dados = resultado.get("dados");
            if (dados != null && dados.isArray()) {
                return dados;
            }
        }
        JsonNode rootDados = root.get("dados");
        if (rootDados != null && rootDados.isArray()) {
            return rootDados;
        }
        return null;
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
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal parseDecimalNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) return null;
        try {
            return new BigDecimal(text.replace(",", "."));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
