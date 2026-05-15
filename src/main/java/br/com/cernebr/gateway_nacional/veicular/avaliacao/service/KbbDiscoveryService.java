package br.com.cernebr.gateway_nacional.veicular.avaliacao.service;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.KbbRouteDTO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovery Layer KBB — traduz {@code (codigoFipe, ano)} numa
 * {@link KbbRouteDTO} contendo {@code slugMarca/slugModelo/slugVersao/kbbId}
 * que o scraper do {@code kbb.com.br} precisa para resolver os preços.
 *
 * <h2>Por que existe</h2>
 * <p>O portal KBB indexa veículos pelo {@code KbbId} interno deles, não pelo
 * código FIPE. O {@code KbbScraperClient} original dependia do operador
 * preencher manualmente um template estático com slug+id — efetivamente
 * impedindo o scraper de funcionar para qualquer veículo fora de uma lista
 * cravada em config. Este serviço elimina esse acoplamento: dado o código
 * FIPE + ano + dicas (marca/modelo), o Discovery resolve a URL exata.</p>
 *
 * <h2>Estratégia híbrida em 3 níveis</h2>
 * <ul>
 *   <li><b>L1 — Índice em memória ({@code ConcurrentMap})</b>: ponto de fuga
 *       O(1) consultado primeiro. Populado por SEED (boot) + RUNTIME (persistido
 *       em disco) + DYNAMIC (descoberto em tempo real). Toda descoberta
 *       bem-sucedida promove a entrada para L1 antes de devolver — a próxima
 *       requisição para o mesmo {fipe,ano} resolve em microssegundos.</li>
 *   <li><b>L2 — SEED ({@code classpath:data/fipe_kbb_map.json})</b>: curadoria
 *       do operador, versionada no git. Garante que reinícios partam de uma
 *       base sólida (FIPEs populares já mapeados) sem dependência de I/O.</li>
 *   <li><b>L3 — Dynamic Discovery via FlareSolverr/Jsoup</b>: na ausência de
 *       L1/L2, o serviço simula a navegação do usuário no portal KBB. Primeiro
 *       a página da marca ({@code /sp/marcas/{marca}/}) é consultada para
 *       descobrir o par (modelo, categoria); em seguida a página de versões
 *       do ano ({@code /sp/marcas/{marca}/{categoria}/{modelo}/{ano}/}) é
 *       raspada para extrair o link canônico
 *       {@code /sp/{ano}/{categoria}/{marca}/{modelo}/{versao}/{kbbId}/preco-de-loja/}
 *       — daí saem o {@code KbbId} e a {@code versao}.</li>
 * </ul>
 *
 * <h2>Persistência local (auditoria + warm start)</h2>
 * <p>Cada descoberta DYNAMIC bem-sucedida é serializada em
 * {@code tmp/kbb-discovery-cache.json} (configurável via
 * {@code gateway.avaliacao.kbb.discovery.persist-file}). No próximo boot, o
 * conteúdo é re-hidratado em L1 antes de qualquer requisição — o cluster volta
 * "morno" sem precisar reaprender rotas. O arquivo é mantido legível para que
 * auditoria humana valide as rotas descobertas sem precisar reproduzir o
 * scraping.</p>
 *
 * <h2>Fail-soft</h2>
 * <p>Quando o FlareSolverr está desligado ou a busca dinâmica não encontra
 * nenhuma rota correspondente, o método devolve {@link Optional#empty()}. O
 * scraper consumidor traduz isso em {@code disponivel=false} no
 * {@code PrecoKbbDTO} — nunca propaga exceção para o orquestrador de Virtual
 * Threads. Veículo desconhecido é estado permanente, não erro.</p>
 */
@Slf4j
@Service
public class KbbDiscoveryService {

    /** Origem L2 — entrada veio do JSON estático do classpath. */
    public static final String SOURCE_SEED = "SEED";
    /** Origem L3 — entrada descoberta em runtime via FlareSolverr+Jsoup. */
    public static final String SOURCE_DYNAMIC = "DYNAMIC";
    /** Origem L1 — entrada recuperada do cache persistido em disco no boot. */
    public static final String SOURCE_RUNTIME = "RUNTIME";

    private static final String SEED_CLASSPATH = "data/fipe_kbb_map.json";

    /** Limita o número de modelos rastreados na página da marca — defesa contra HTMLs absurdos. */
    private static final int MAX_BRAND_MODELS = 256;

    /**
     * Captura o href canônico de uma versão na página de listagem:
     * {@code /sp/{ano}/{categoria}/{marca}/{modelo}/{versao}/{kbbId}/preco-de-{canal}/}.
     */
    private static final Pattern PRICE_HREF_PATTERN = Pattern.compile(
            "href=\"(/sp/(\\d{4})/([a-z0-9-]+)/([a-z0-9-]+)/([a-z0-9-]+)/([a-z0-9-]+)/(\\d+)/preco-de-[a-z]+/)\"");

    private final FlareSolverrInvoker flareSolverr;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Path persistFile;

    private final ConcurrentMap<String, KbbRouteDTO> index = new ConcurrentHashMap<>();

    public KbbDiscoveryService(
            FlareSolverrInvoker flareSolverr,
            ObjectMapper objectMapper,
            @Value("${gateway.avaliacao.kbb.base-url:https://kbb.com.br}") String baseUrl,
            @Value("${gateway.avaliacao.kbb.discovery.persist-file:tmp/kbb-discovery-cache.json}") String persistFile) {
        this.flareSolverr = flareSolverr;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.persistFile = Path.of(persistFile);
    }

    @PostConstruct
    void warmupIndex() {
        int seedCount = loadClasspathSeed();
        int runtimeCount = loadRuntimePersist();
        log.info("KbbDiscoveryService inicializado: índice L1 com {} rotas ({} seed + {} runtime).",
                index.size(), seedCount, runtimeCount);
    }

    /**
     * Resolve a rota canônica para o veículo identificado por {@code codigoFipe}
     * + {@code anoModelo}. Estratégia 3-níveis (L1 cache → L2 seed → L3 dynamic).
     * Retorna {@link Optional#empty()} apenas quando nada da cascata bateu.
     */
    public Optional<KbbRouteDTO> discover(String codigoFipe, String marca, String modelo, int anoModelo) {
        if (codigoFipe == null || codigoFipe.isBlank()) {
            return Optional.empty();
        }
        String key = buildKey(codigoFipe, anoModelo);

        long lookupStart = System.nanoTime();
        KbbRouteDTO cached = index.get(key);
        if (cached != null) {
            long elapsedMicros = (System.nanoTime() - lookupStart) / 1_000;
            log.info("KBB Discovery [L1 CACHE HIT] fipe={} ano={} source={} kbbId={} slug={}/{}/{} url={} latency={}us",
                    codigoFipe, anoModelo, cached.source(), cached.kbbId(),
                    cached.slugMarca(), cached.slugModelo(), cached.slugVersao(),
                    cached.urlReferencia(), elapsedMicros);
            return Optional.of(cached);
        }

        log.info("KBB Discovery [L1 CACHE MISS] fipe={} ano={} marca='{}' modelo='{}' — disparando busca dinâmica na KBB.",
                codigoFipe, anoModelo, marca, modelo);

        long t0 = System.currentTimeMillis();
        Optional<KbbRouteDTO> discovered = dynamicSearch(codigoFipe, marca, modelo, anoModelo);
        long elapsedMillis = System.currentTimeMillis() - t0;

        if (discovered.isPresent()) {
            KbbRouteDTO route = discovered.get();
            index.put(key, route);
            persistRuntime();
            log.info("KBB Discovery [DYNAMIC OK] fipe={} ano={} kbbId={} categoria={} slug={}/{}/{} url={} latency={}ms",
                    codigoFipe, anoModelo, route.kbbId(), route.categoria(),
                    route.slugMarca(), route.slugModelo(), route.slugVersao(),
                    route.urlReferencia(), elapsedMillis);
            return discovered;
        }

        log.warn("KBB Discovery [DYNAMIC MISS] fipe={} ano={} marca='{}' modelo='{}' — nenhuma rota localizada após {}ms.",
                codigoFipe, anoModelo, marca, modelo, elapsedMillis);
        return Optional.empty();
    }

    /**
     * Variante "somente cache" — consulta apenas L1, nunca dispara busca
     * dinâmica. Útil para paths de erro/auditoria (ex. fallback de Circuit
     * Breaker) onde uma chamada gratuita ao FlareSolverr seria prejudicial.
     */
    public Optional<KbbRouteDTO> peek(String codigoFipe, int anoModelo) {
        if (codigoFipe == null || codigoFipe.isBlank()) return Optional.empty();
        return Optional.ofNullable(index.get(buildKey(codigoFipe, anoModelo)));
    }

    /** Acesso de leitura ao snapshot atual do índice — usado por endpoints de auditoria. */
    public Map<String, KbbRouteDTO> snapshot() {
        return Map.copyOf(index);
    }

    /** Tamanho atual do índice em memória. */
    public int size() {
        return index.size();
    }

    // ----------------------------------------------------------------------
    // L3 — Dynamic Discovery via FlareSolverr + Jsoup
    // ----------------------------------------------------------------------

    private Optional<KbbRouteDTO> dynamicSearch(String fipeCode, String marca, String modelo, int anoModelo) {
        if (!flareSolverr.isEnabled()) {
            log.warn("KBB Discovery: FlareSolverr desligado — busca dinâmica impossível, apenas L1/L2 disponíveis.");
            return Optional.empty();
        }
        String slugMarca = slugify(marca);
        String slugModeloHint = slugify(modelo);
        if (slugMarca.isEmpty() || slugModeloHint.isEmpty()) {
            log.warn("KBB Discovery: marca/modelo vazios — não há como construir a query (marca='{}' modelo='{}').",
                    marca, modelo);
            return Optional.empty();
        }

        try {
            // Passo 1: página da marca → descobre (modelo → categoria) e o slug canônico do modelo.
            String brandUrl = baseUrl + "/sp/marcas/" + slugMarca + "/";
            FlareSolverrInvoker.FlareResult brandResp = flareSolverr.get(brandUrl);
            String brandHtml = brandResp.html();
            if (brandHtml == null || brandHtml.isBlank()) {
                log.warn("KBB Discovery: página da marca '{}' veio vazia (url={}).", slugMarca, brandUrl);
                return Optional.empty();
            }

            ModelMatch matched = pickModelAndCategoria(brandHtml, slugMarca, slugModeloHint, anoModelo);
            if (matched == null) {
                log.warn("KBB Discovery: marca='{}' não publica modelo '{}' para ano={} (brand page parse falhou).",
                        slugMarca, slugModeloHint, anoModelo);
                return Optional.empty();
            }
            log.debug("KBB Discovery: marca={} resolveu modelo='{}' categoria='{}' para ano={}.",
                    slugMarca, matched.slugModelo, matched.categoria, anoModelo);

            // Passo 2: página de versões do ano → primeira versão com KbbId virá o vencedor.
            String versionsUrl = baseUrl + "/sp/marcas/" + slugMarca + "/" + matched.categoria
                    + "/" + matched.slugModelo + "/" + anoModelo + "/";
            FlareSolverrInvoker.FlareResult versionsResp = flareSolverr.get(versionsUrl);
            String versionsHtml = versionsResp.html();
            if (versionsHtml == null || versionsHtml.isBlank()) {
                log.warn("KBB Discovery: página de versões veio vazia (url={}).", versionsUrl);
                return Optional.empty();
            }

            Matcher m = PRICE_HREF_PATTERN.matcher(versionsHtml);
            if (!m.find()) {
                log.warn("KBB Discovery: nenhum link preco-de-* encontrado em {} — modelo pode não ter sido produzido nesse ano.",
                        versionsUrl);
                return Optional.empty();
            }

            int ano = Integer.parseInt(m.group(2));
            String categoriaCanonica = m.group(3);
            String marcaCanonica = m.group(4);
            String modeloCanonico = m.group(5);
            String versaoCanonica = m.group(6);
            int kbbId = Integer.parseInt(m.group(7));
            String urlTemplate = baseUrl + "/sp/" + ano + "/" + categoriaCanonica
                    + "/" + marcaCanonica + "/" + modeloCanonico + "/" + versaoCanonica
                    + "/" + kbbId + "/preco-de-{canal}/";

            return Optional.of(new KbbRouteDTO(fipeCode, kbbId, marcaCanonica, modeloCanonico,
                    versaoCanonica, categoriaCanonica, ano, urlTemplate, SOURCE_DYNAMIC, Instant.now()));
        } catch (Exception ex) {
            log.warn("KBB Discovery: busca dinâmica falhou para fipe={} ano={} — {}: {}",
                    fipeCode, anoModelo, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Varre a página da marca buscando todas as ocorrências
     * {@code /sp/marcas/{marca}/{categoria}/{modelo}/{ano}/} e elege o modelo
     * cuja slug bata melhor com a dica recebida. Estratégia, em ordem:
     * <ol>
     *   <li>match exato com o slug do modelo entregue;</li>
     *   <li>match por prefixo (o slug entregue começa com o publicado, ex.
     *       "onix-lt-1-0-flex" começa com "onix");</li>
     *   <li>match por substring (o slug publicado aparece dentro do entregue).</li>
     * </ol>
     */
    private ModelMatch pickModelAndCategoria(String brandHtml, String slugMarca, String slugModeloHint, int anoModelo) {
        Pattern p = Pattern.compile("/sp/marcas/" + Pattern.quote(slugMarca)
                + "/([a-z0-9-]+)/([a-z0-9-]+)/(\\d{4})/?");
        Matcher m = p.matcher(brandHtml);
        // Preserva ordem de descoberta — modelo mais "central" na navegação tende a aparecer antes.
        Map<String, String> modelToCategoria = new LinkedHashMap<>();
        List<String> modelosDoAno = new ArrayList<>();
        int seen = 0;
        while (m.find() && seen < MAX_BRAND_MODELS) {
            String categoria = m.group(1);
            String modeloSlug = m.group(2);
            int ano = Integer.parseInt(m.group(3));
            modelToCategoria.putIfAbsent(modeloSlug, categoria);
            if (ano == anoModelo) {
                modelosDoAno.add(modeloSlug);
            }
            seen++;
        }
        if (modelToCategoria.isEmpty()) return null;

        // Match exato — pegar apenas dos modelos que apareceram para o ano-alvo se possível.
        List<String> candidatos = modelosDoAno.isEmpty() ? new ArrayList<>(modelToCategoria.keySet()) : modelosDoAno;
        if (candidatos.contains(slugModeloHint)) {
            return new ModelMatch(slugModeloHint, modelToCategoria.get(slugModeloHint));
        }
        // Prefixo — slugModeloHint começa com algum candidato (ex: input "onix-lt" e existir "onix").
        for (String c : candidatos) {
            if (slugModeloHint.equals(c) || slugModeloHint.startsWith(c + "-")) {
                return new ModelMatch(c, modelToCategoria.get(c));
            }
        }
        // Substring — candidato aparece dentro do slugModeloHint.
        for (String c : candidatos) {
            if (slugModeloHint.contains(c)) {
                return new ModelMatch(c, modelToCategoria.get(c));
            }
        }
        return null;
    }

    private record ModelMatch(String slugModelo, String categoria) {
    }

    // ----------------------------------------------------------------------
    // L2 / L1 persistence
    // ----------------------------------------------------------------------

    private int loadClasspathSeed() {
        ClassPathResource resource = new ClassPathResource(SEED_CLASSPATH);
        if (!resource.exists()) {
            log.info("KBB Discovery: classpath:{} ausente — partindo com índice vazio.", SEED_CLASSPATH);
            return 0;
        }
        try (InputStream stream = resource.getInputStream()) {
            List<KbbRouteDTO> rows = objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, KbbRouteDTO.class));
            int loaded = 0;
            for (KbbRouteDTO raw : rows) {
                if (raw == null || raw.fipeCode() == null || raw.anoModelo() == null) continue;
                KbbRouteDTO seeded = new KbbRouteDTO(raw.fipeCode(), raw.kbbId(),
                        raw.slugMarca(), raw.slugModelo(), raw.slugVersao(),
                        raw.categoria(), raw.anoModelo(), raw.urlTemplate(),
                        SOURCE_SEED,
                        raw.discoveredAt() != null ? raw.discoveredAt() : Instant.now());
                index.put(buildKey(seeded.fipeCode(), seeded.anoModelo()), seeded);
                loaded++;
            }
            log.info("KBB Discovery: {} rotas SEED carregadas de classpath:{}.", loaded, SEED_CLASSPATH);
            return loaded;
        } catch (Exception ex) {
            log.warn("KBB Discovery: falha lendo classpath:{} ({}: {}) — ignorado.",
                    SEED_CLASSPATH, ex.getClass().getSimpleName(), ex.getMessage());
            return 0;
        }
    }

    private int loadRuntimePersist() {
        try {
            if (!Files.exists(persistFile)) return 0;
            byte[] bytes = Files.readAllBytes(persistFile);
            if (bytes.length == 0) return 0;
            List<KbbRouteDTO> rows = objectMapper.readValue(bytes,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, KbbRouteDTO.class));
            int loaded = 0;
            for (KbbRouteDTO raw : rows) {
                if (raw == null || raw.fipeCode() == null || raw.anoModelo() == null) continue;
                index.put(buildKey(raw.fipeCode(), raw.anoModelo()), raw.withSource(SOURCE_RUNTIME));
                loaded++;
            }
            log.info("KBB Discovery: {} rotas RUNTIME re-hidratadas de {}.", loaded, persistFile);
            return loaded;
        } catch (Exception ex) {
            log.warn("KBB Discovery: arquivo runtime {} ilegível ({}: {}) — ignorado.",
                    persistFile, ex.getClass().getSimpleName(), ex.getMessage());
            return 0;
        }
    }

    /**
     * Serializa rotas DYNAMIC/RUNTIME para o arquivo de cache local. Best-effort
     * — falhas são logadas e ignoradas. A rota descoberta já está em L1, então
     * mesmo sem o arquivo a próxima chamada in-memory continua O(1).
     */
    private synchronized void persistRuntime() {
        try {
            List<KbbRouteDTO> snapshot = new ArrayList<>();
            for (KbbRouteDTO r : index.values()) {
                if (SOURCE_SEED.equals(r.source())) continue;
                snapshot.add(r);
            }
            Path parent = persistFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(snapshot);
            Files.write(persistFile, bytes);
        } catch (Exception ex) {
            log.debug("KBB Discovery: persistRuntime ignorado ({}: {}).",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    // utils
    // ----------------------------------------------------------------------

    private static String buildKey(String fipe, int ano) {
        return fipe + "::" + ano;
    }

    private static String buildKey(String fipe, Integer ano) {
        return fipe + "::" + ano;
    }

    /**
     * Mesma regra de slugify do {@code ScraperSupport}, replicada aqui para não
     * acoplar o serviço de {@code service} ao package {@code client}. Lowercase,
     * folding NFD para ASCII e separação por hífen — idempotente.
     */
    static String slugify(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
