package br.com.cernebr.gateway_nacional.veicular.fipe.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeMarcaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTabelaReferenciaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTipoVeiculo;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeVeiculoResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Primary FIPE provider — direct scraper of the official FIPE API
 * ({@code veiculos.fipe.org.br}) mediated by the FlareSolverr sidecar.
 *
 * <h2>Why this exists</h2>
 * <p>BrasilAPI and Parallelum both proxy {@code veiculos.fipe.org.br}
 * server-side, and at the time of this writing both proxies are blocked by
 * the FIPE upstream — BrasilAPI returns HTTP 500 with an embedded
 * {@code AxiosError: status 403}, and Parallelum's direct-by-fipe-code path
 * (v1) was retired. Going to the foundation directly via FlareSolverr
 * sidesteps both intermediaries; the official API only checks for browser-
 * grade challenge handshakes, which FlareSolverr's headless Chromium solves
 * transparently.</p>
 *
 * <h2>Two-step protocol (per call, in a persistent session)</h2>
 * <ol>
 *   <li>Warm-up GET on {@code https://veiculos.fipe.org.br/} — required for
 *       the IIS backend to issue the session cookies that gate the API
 *       endpoints. A fresh Chromium context per call would trip the backend
 *       into returning the canonical IIS "resource not found" page.</li>
 *   <li>POST {@code /api/veiculos/ConsultarTabelaDeReferencia} (no body) →
 *       array of {@code {Codigo, Mes}} with the most recent month at index 0.
 *       Cached in-process for {@link #REF_TABLE_TTL} since the value flips
 *       at most once per calendar month.</li>
 *   <li>POST {@code /api/veiculos/ConsultarValorComTodosParametros} with the
 *       form fields below. Iterates {@link #FUEL_CODES} until one returns a
 *       priced response — see "Fuel inference" below.</li>
 * </ol>
 *
 * <h2>Form contract — discovered empirically</h2>
 * <p>The FIPE wizard's "consulta por código" path expects:
 * <ul>
 *   <li>{@code codigoTabelaReferencia} — int from {@code ConsultarTabelaDeReferencia[0].Codigo};</li>
 *   <li>{@code codigoTipoVeiculo=1} — 1=cars, 2=motorcycles, 3=trucks (we hardcode 1);</li>
 *   <li>{@code modeloCodigoExterno} — the FIPE code in {@code "000000-0"} format
 *       (NOT {@code codigoFipe}, despite the name; that field rejects with
 *       {@code "Parâmetros inválidos"});</li>
 *   <li>{@code codigoTipoCombustivel} — see {@link #FUEL_CODES};</li>
 *   <li>{@code anoModelo} — bare year as int (no {@code "-1"} suffix);</li>
 *   <li>{@code tipoVeiculo=carro};</li>
 *   <li>{@code tipoConsulta=codigo}.</li>
 * </ul>
 *
 * <h2>Fuel inference</h2>
 * <p>The FIPE database keys quotes by {@code (codigoFipe, anoModelo, codigoTipoCombustivel)}.
 * Two cars with the same code and year but different fuels (e.g., flex vs. gasoline) are
 * separate records. The wizard exposes the fuel via the {@code anoModelo} value's suffix
 * ({@code 2014-5} = Flex, {@code 2014-1} = Gasoline) — but our public contract takes only
 * {@code codigoFipe + anoModelo} (no fuel hint). So we sweep {@link #FUEL_CODES} in order
 * of empirical commonality (Flex first, then Gasoline, Diesel, Alcohol, Electric/Hybrid)
 * and return the first hit. Worst case is 5 POSTs; the typical case is 1.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>{@code {"erro":"Parâmetros inválidos"}} — payload schema is wrong; bubbles
 *       as {@link ResourceUnavailableException} (signals contract drift, halts the sweep);</li>
 *   <li>{@code {"erro":"nadaencontrado"}} — schema is right, this fuel/year/code triple
 *       has no record; sweep continues to the next fuel;</li>
 *   <li>HTML page with {@code "resource you are looking for"} — endpoint was removed/renamed;
 *       bubbles as {@link ResourceUnavailableException}.</li>
 * </ul>
 *
 * <p>When {@code gateway.flaresolverr.url} is empty, the call short-circuits with the
 * standard {@code "Esta rota exige a ativação do sidecar FlareSolverr..."} message — fast 503.</p>
 */
@Slf4j
@Component
public class FipeOrgScraperClient implements FipeClientProvider, FipeNavegacaoProvider {

    public static final String PROVIDER_NAME = "FIPE-Oficial";
    static final String FLARE_REQUIRED_MESSAGE =
            "Esta rota exige a ativação do sidecar FlareSolverr — provedor primário da FIPE depende dele.";

    /** Order: Flex (most common in the BR fleet), Gasoline, Diesel, Alcohol, Electric/Hybrid. */
    static final List<Integer> FUEL_CODES = List.of(5, 1, 3, 2, 6);

    /** Reference table flips once per calendar month; cache up to 6h. */
    private static final Duration REF_TABLE_TTL = Duration.ofHours(6);

    private static final String API_PATH = "/api/veiculos/";
    private static final String REF_TABLE_PATH = API_PATH + "ConsultarTabelaDeReferencia";
    private static final String VALUE_PATH = API_PATH + "ConsultarValorComTodosParametros";
    private static final String MARCAS_PATH = API_PATH + "ConsultarMarcas";
    private static final String MODELOS_PATH = API_PATH + "ConsultarModelos";

    private final String baseUrl;
    private final FlareSolverrInvoker flareSolverr;
    private final ObjectMapper objectMapper;

    /** In-process cache of the current FIPE reference month code (e.g., 333 for May/2026). */
    private volatile Integer cachedRefTable;
    private volatile long cachedRefTableAt;

    public FipeOrgScraperClient(@Value("${gateway.fipe.fipeorg.base-url:https://veiculos.fipe.org.br}") String baseUrl,
                                FlareSolverrInvoker flareSolverr,
                                ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.flareSolverr = flareSolverr;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "fipeOrgScraperCB", fallbackMethod = "fallback")
    public FipePrecoResponse fetchPreco(String codigoFipe, String anoModelo) {
        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }
        int year = parseYear(codigoFipe, anoModelo);

        String session = flareSolverr.createSession();
        try {
            // Warm-up: the IIS backend issues session cookies on the homepage and
            // gates the API endpoints behind them. A bare POST in a fresh Chromium
            // context returns the IIS "resource not found" template.
            flareSolverr.getInSession(baseUrl + "/", session);

            int refTable = ensureReferenceTable(session);

            for (int comb : FUEL_CODES) {
                FipeOrgPayload payload = tryConsultaPorCodigo(session, refTable, codigoFipe, year, comb);
                if (payload != null) {
                    log.info("FIPE-Oficial resolved codigoFipe={} ano={} via combustível={} → {}",
                            codigoFipe, year, comb, payload.valor());
                    return payload.toResponse();
                }
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE oficial não encontrou veículo para codigoFipe=" + codigoFipe
                            + " anoModelo=" + year + " em nenhum tipo de combustível conhecido. "
                            + "Verifique se o código existe na tabela de referência atual.");
        } finally {
            flareSolverr.destroySession(session);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private FipePrecoResponse fallback(String codigoFipe, String anoModelo, Throwable cause) {
        log.warn("FIPE-Oficial fallback triggered for codigoFipe={} anoModelo={} cause={}",
                codigoFipe, anoModelo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "FIPE oficial indisponível ou Circuit Breaker aberto.", cause);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navegação FIPE (FipeNavegacaoProvider) — marcas, veículos, tabelas-ref.
    // Os 3 endpoints abaixo compartilham a mesma session FlareSolverr do
    // fetchPreco e usam o mesmo CB ({@code fipeOrgScraperCB}) — saturação de
    // navegação afeta a saúde percebida da cotação, intencional pra que
    // navegação pesada não destrua o CB sozinha.
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @CircuitBreaker(name = "fipeOrgScraperCB", fallbackMethod = "fallbackMarcas")
    public List<FipeMarcaResponse> listMarcas(FipeTipoVeiculo tipo, @Nullable Integer tabelaReferencia) {
        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }
        String session = flareSolverr.createSession();
        try {
            flareSolverr.getInSession(baseUrl + "/", session);
            int refTable = tabelaReferencia != null ? tabelaReferencia : ensureReferenceTable(session);
            return fetchMarcas(session, tipo, refTable);
        } finally {
            flareSolverr.destroySession(session);
        }
    }

    @Override
    @CircuitBreaker(name = "fipeOrgScraperCB", fallbackMethod = "fallbackVeiculos")
    public List<FipeVeiculoResponse> listVeiculosByMarca(FipeTipoVeiculo tipo,
                                                        String codigoMarca,
                                                        @Nullable Integer tabelaReferencia) {
        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }
        String session = flareSolverr.createSession();
        try {
            flareSolverr.getInSession(baseUrl + "/", session);
            int refTable = tabelaReferencia != null ? tabelaReferencia : ensureReferenceTable(session);
            return fetchVeiculos(session, tipo, codigoMarca, refTable);
        } finally {
            flareSolverr.destroySession(session);
        }
    }

    @Override
    @CircuitBreaker(name = "fipeOrgScraperCB", fallbackMethod = "fallbackTabelas")
    public List<FipeTabelaReferenciaResponse> listTabelasReferencia() {
        if (!flareSolverr.isEnabled()) {
            throw new ResourceUnavailableException(PROVIDER_NAME, FLARE_REQUIRED_MESSAGE);
        }
        String session = flareSolverr.createSession();
        try {
            flareSolverr.getInSession(baseUrl + "/", session);
            return fetchTabelasReferencia(session);
        } finally {
            flareSolverr.destroySession(session);
        }
    }

    @SuppressWarnings("unused")
    private List<FipeMarcaResponse> fallbackMarcas(FipeTipoVeiculo tipo, Integer tabelaReferencia, Throwable cause) {
        log.warn("FIPE-Oficial fallback marcas tipo={} tabela={} cause={}", tipo, tabelaReferencia, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "FIPE oficial indisponível ou Circuit Breaker aberto (marcas).", cause);
    }

    @SuppressWarnings("unused")
    private List<FipeVeiculoResponse> fallbackVeiculos(FipeTipoVeiculo tipo, String codigoMarca,
                                                      Integer tabelaReferencia, Throwable cause) {
        log.warn("FIPE-Oficial fallback veiculos tipo={} marca={} tabela={} cause={}",
                tipo, codigoMarca, tabelaReferencia, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "FIPE oficial indisponível ou Circuit Breaker aberto (veiculos).", cause);
    }

    @SuppressWarnings("unused")
    private List<FipeTabelaReferenciaResponse> fallbackTabelas(Throwable cause) {
        log.warn("FIPE-Oficial fallback tabelas cause={}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "FIPE oficial indisponível ou Circuit Breaker aberto (tabelas).", cause);
    }

    /**
     * POST {@code /api/veiculos/ConsultarMarcas} — devolve {@code [{Label, Value}]}
     * que mapeamos para {@code [{nome, valor}]}, ordenado por valor numérico crescente
     * (espelha o {@code .sort((a, b) => parseInt(a.valor) - parseInt(b.valor))} da BrasilAPI).
     */
    private List<FipeMarcaResponse> fetchMarcas(String session, FipeTipoVeiculo tipo, int refTable) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("codigoTabelaReferencia", String.valueOf(refTable));
        form.put("codigoTipoVeiculo", String.valueOf(tipo.fipeCodigoVeiculo()));

        FlareSolverrInvoker.FlareResult result = flareSolverr.postInSession(baseUrl + MARCAS_PATH, form, session);
        JsonNode array = parseJsonArray(result.jsonBody(), "ConsultarMarcas");

        List<FipeMarcaResponse> marcas = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String nome = textOrEmpty(item, "Label");
            String valor = textOrEmpty(item, "Value");
            if (nome.isBlank() || valor.isBlank()) continue;
            marcas.add(new FipeMarcaResponse(nome, valor));
        }
        marcas.sort(Comparator.comparingInt(m -> safeParseInt(m.valor())));
        return marcas;
    }

    /**
     * POST {@code /api/veiculos/ConsultarModelos} — devolve {@code {Modelos: [{Label, Value}], ...}}.
     * A BrasilAPI dropa o {@code Value} (devolve só {@code modelo}); aqui preservamos os dois,
     * porque o ID interno é útil pra chamadas subsequentes (ex: refinamento por ano-modelo).
     */
    private List<FipeVeiculoResponse> fetchVeiculos(String session, FipeTipoVeiculo tipo,
                                                   String codigoMarca, int refTable) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("codigoTabelaReferencia", String.valueOf(refTable));
        form.put("codigoTipoVeiculo", String.valueOf(tipo.fipeCodigoVeiculo()));
        form.put("codigoMarca", codigoMarca);

        FlareSolverrInvoker.FlareResult result = flareSolverr.postInSession(baseUrl + MODELOS_PATH, form, session);
        JsonNode root = parseJsonObject(result.jsonBody(), "ConsultarModelos");

        JsonNode modelos = root.get("Modelos");
        if (modelos == null || !modelos.isArray()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: ConsultarModelos retornou objeto sem array \"Modelos\".");
        }

        List<FipeVeiculoResponse> veiculos = new ArrayList<>(modelos.size());
        for (JsonNode item : modelos) {
            String nome = textOrEmpty(item, "Label");
            String valor = textOrEmpty(item, "Value");
            if (nome.isBlank()) continue;
            veiculos.add(new FipeVeiculoResponse(nome, valor.isBlank() ? null : valor));
        }
        return veiculos;
    }

    /**
     * Versão pública de {@link #fetchCurrentReferenceTable(String)} — aquela
     * devolvia só o código mais recente; aqui devolvemos a lista completa
     * com ordenação descendente (mais recente primeiro).
     */
    private List<FipeTabelaReferenciaResponse> fetchTabelasReferencia(String session) {
        FlareSolverrInvoker.FlareResult result =
                flareSolverr.postInSession(baseUrl + REF_TABLE_PATH, Map.of(), session);
        JsonNode array = parseJsonArray(result.jsonBody(), "ConsultarTabelaDeReferencia");

        List<FipeTabelaReferenciaResponse> tabelas = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            JsonNode codigo = item.get("Codigo");
            if (codigo == null || !codigo.isInt()) continue;
            String mes = textOrEmpty(item, "Mes").trim();
            tabelas.add(new FipeTabelaReferenciaResponse(codigo.intValue(), mes));
        }
        tabelas.sort(Comparator.comparingInt(FipeTabelaReferenciaResponse::codigo).reversed());
        return tabelas;
    }

    private JsonNode parseJsonArray(String body, String operationName) {
        if (body == null || body.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: corpo vazio em " + operationName + ".");
        }
        if (body.contains("resource you are looking for")) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: " + operationName + " devolveu IIS \"resource not found\" — contrato pode ter mudado.");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!node.isArray() || node.isEmpty()) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "FIPE-Oficial: " + operationName + " devolveu corpo não-array ou vazio.");
            }
            return node;
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: " + operationName + " corpo não-JSON: " + ex.getMessage(), ex);
        }
    }

    private JsonNode parseJsonObject(String body, String operationName) {
        if (body == null || body.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: corpo vazio em " + operationName + ".");
        }
        if (body.contains("resource you are looking for")) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: " + operationName + " devolveu IIS \"resource not found\" — contrato pode ter mudado.");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!node.isObject()) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "FIPE-Oficial: " + operationName + " devolveu corpo não-objeto.");
            }
            return node;
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: " + operationName + " corpo não-JSON: " + ex.getMessage(), ex);
        }
    }

    private static int safeParseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return Integer.MAX_VALUE; }
    }

    /**
     * Returns the most recent FIPE reference table code, fetching it lazily
     * with a {@link #REF_TABLE_TTL} TTL. Cache is in-process (volatile
     * fields, no Redis) — the value is a single int, hot, and safe to
     * recompute across replicas independently.
     */
    private int ensureReferenceTable(String session) {
        long now = System.currentTimeMillis();
        Integer cached = cachedRefTable;
        if (cached != null && (now - cachedRefTableAt) < REF_TABLE_TTL.toMillis()) {
            return cached;
        }
        int fresh = fetchCurrentReferenceTable(session);
        cachedRefTable = fresh;
        cachedRefTableAt = now;
        return fresh;
    }

    private int fetchCurrentReferenceTable(String session) {
        FlareSolverrInvoker.FlareResult result =
                flareSolverr.postInSession(baseUrl + REF_TABLE_PATH, Map.of(), session);
        String body = result.jsonBody();
        if (body == null || body.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: corpo vazio ao consultar tabela de referência.");
        }
        if (body.contains("resource you are looking for")) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: endpoint ConsultarTabelaDeReferencia retornou página IIS de \"recurso não encontrado\" — "
                            + "o contrato pode ter mudado novamente.");
        }
        try {
            JsonNode array = objectMapper.readTree(body);
            if (!array.isArray() || array.isEmpty()) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "FIPE-Oficial: tabela de referência veio vazia ou em formato inesperado.");
            }
            JsonNode first = array.get(0);
            JsonNode codigo = first.get("Codigo");
            if (codigo == null || !codigo.isInt()) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "FIPE-Oficial: primeiro item da tabela de referência sem campo \"Codigo\" int.");
            }
            return codigo.intValue();
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: tabela de referência não-JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * Returns the parsed payload when the FIPE backend responds with a priced
     * record, or {@code null} when the backend says {@code "nadaencontrado"}
     * (this fuel/year/code triple has no record — try the next fuel).
     * Throws {@link ResourceUnavailableException} on contract violations
     * (schema mismatch, removed endpoint).
     */
    private FipeOrgPayload tryConsultaPorCodigo(String session, int refTable,
                                                String codigoFipe, int year, int combustivelCode) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("codigoTabelaReferencia", String.valueOf(refTable));
        form.put("codigoTipoVeiculo", "1");
        form.put("modeloCodigoExterno", codigoFipe);
        form.put("codigoTipoCombustivel", String.valueOf(combustivelCode));
        form.put("anoModelo", String.valueOf(year));
        form.put("tipoVeiculo", "carro");
        form.put("tipoConsulta", "codigo");

        FlareSolverrInvoker.FlareResult result =
                flareSolverr.postInSession(baseUrl + VALUE_PATH, form, session);
        String body = result.jsonBody();
        if (body == null || body.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: corpo vazio ao consultar preço (combustivel=" + combustivelCode + ").");
        }
        if (body.contains("resource you are looking for")) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: endpoint ConsultarValorComTodosParametros desapareceu (IIS \"resource not found\").");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial: corpo não-JSON ao consultar preço: " + ex.getMessage(), ex);
        }

        // {"codigo":"0","erro":"nadaencontrado"} — this fuel doesn't have this code/year. Sweep on.
        // {"codigo":"2","erro":"Parâmetros inválidos"} — schema is wrong; halt the sweep.
        JsonNode erro = root.get("erro");
        if (erro != null && !erro.isNull()) {
            String erroText = erro.asText("");
            if ("nadaencontrado".equalsIgnoreCase(erroText)) {
                return null;
            }
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "FIPE-Oficial recusou a consulta: " + erroText
                            + " (combustivel=" + combustivelCode + "). Schema do form pode ter mudado.");
        }

        // Successful payload has Valor + CodigoFipe.
        JsonNode valor = root.get("Valor");
        if (valor == null || valor.isNull()) {
            return null;
        }

        return new FipeOrgPayload(
                valor.asText(""),
                textOrEmpty(root, "Marca"),
                textOrEmpty(root, "Modelo"),
                root.get("AnoModelo") != null ? root.get("AnoModelo").intValue() : year,
                textOrEmpty(root, "Combustivel"),
                textOrEmpty(root, "CodigoFipe"),
                textOrEmpty(root, "MesReferencia").trim()
        );
    }

    private static int parseYear(String codigoFipe, String anoModelo) {
        try {
            return Integer.parseInt(anoModelo);
        } catch (NumberFormatException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Ano modelo inválido para FIPE-Oficial (codigoFipe=" + codigoFipe
                            + ", anoModelo=" + anoModelo + ")", ex);
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child == null || child.isNull()) ? "" : child.asText("");
    }

    /**
     * Strips Brazilian currency formatting ({@code "R$ 34.253,00"}) into a
     * {@link BigDecimal}. Same logic used by the BrasilAPI/Parallelum
     * clients — duplicated here because each ACL owns its own parsing
     * (single-responsibility per provider).
     */
    private static BigDecimal parseBRCurrency(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            return null;
        }
        String cleaned = formatted
                .replace("R$", "")
                .replace(".", "")
                .replace(",", ".")
                .trim();
        return new BigDecimal(cleaned);
    }

    /**
     * Internal Anti-Corruption Layer record — mirrors the FIPE-Oficial wire
     * shape verbatim (PascalCase fields, BR currency string), and converts
     * to the project-canonical {@link FipePrecoResponse} via {@link #toResponse()}.
     */
    private record FipeOrgPayload(
            String valor,
            String marca,
            String modelo,
            int anoModelo,
            String combustivel,
            String codigoFipe,
            String mesReferencia
    ) {
        FipePrecoResponse toResponse() {
            return new FipePrecoResponse(
                    codigoFipe,
                    marca,
                    modelo,
                    anoModelo,
                    combustivel,
                    parseBRCurrency(valor),
                    normalizeMesReferencia(mesReferencia)
            );
        }

        /**
         * FIPE-Oficial returns {@code "maio de 2026 "} (Portuguese long form).
         * BrasilAPI and Parallelum return {@code "maio de 2026"} (no trailing
         * space) — we strip the trailing whitespace so consumers caching by
         * provider don't see false drifts. Format is otherwise preserved
         * verbatim across the cascade.
         */
        private static String normalizeMesReferencia(String raw) {
            if (raw == null) return null;
            String trimmed = raw.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
