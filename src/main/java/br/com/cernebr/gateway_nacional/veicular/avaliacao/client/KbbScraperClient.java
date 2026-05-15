package br.com.cernebr.gateway_nacional.veicular.avaliacao.client;

import br.com.cernebr.gateway_nacional.config.FlareSolverrInvoker;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.FaixaPrecoKbb;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.PrecoKbbDTO;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.VariacaoConservacaoKbb;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provedor KBB — Avaliação Técnica via raspagem do portal {@code kbb.com.br}
 * mediada pelo {@link FlareSolverrInvoker}.
 *
 * <h2>Por que FlareSolverr</h2>
 * <p>O portal KBB serve via CDN Cloudflare e intercala desafios JS quando
 * detecta tráfego automatizado. {@link Jsoup} sozinho recebe a página de
 * challenge ao invés do conteúdo real. O sidecar resolve o desafio com
 * Chromium headless e devolve o HTML pós-resolução.</p>
 *
 * <h2>Estrutura real do KBB (Next.js streaming flight)</h2>
 * <p>O portal é uma aplicação Next.js (App Router). Os preços <b>não</b> são
 * renderizados como texto BRL no DOM — eles vivem em um blob JSON
 * embutido em scripts {@code self.__next_f.push([...])} que o cliente hidrata
 * progressivamente. O campo central é {@code vehiclePrices}:
 * <pre>
 * "vehiclePrices": {
 *     "KbbId": 1294,
 *     "priceType": 4,
 *     "suggestedPrice": 36120,
 *     "kbbPrice": 37897.29,
 *     "priceRangeStart": 36531.85,
 *     "priceRangeEnd": 39262.73,
 *     "state": "SE"
 * }
 * </pre>
 * O {@code priceType} indica o canal:
 * {@code 1} = preço de loja, {@code 2} = preço de troca,
 * {@code 4} = preço de particular. As três URLs são distintas
 * ({@code /preco-de-loja/}, {@code /preco-de-troca/},
 * {@code /preco-de-particular/}) e cada uma carrega <i>apenas</i> o seu
 * próprio canal — daí o scraper fazer duas requisições em paralelo (troca +
 * particular) e mesclar.</p>
 *
 * <h2>URL slug-based</h2>
 * <p>O KBB indexa veículos pelo próprio {@code KbbId} interno, não pelo
 * código FIPE. O template configurável aceita placeholders
 * {@code {ano} {categoria} {marca} {modelo} {versao} {kbbId} {canal}}, e
 * o operador é responsável por traduzir um código FIPE em slug+id via uma
 * camada de discovery (sitemap, API de busca, mapeamento próprio). Para
 * cenários onde a tradução não está disponível, o scraper degrada para
 * indisponibilidade graciosa — nunca 500.</p>
 *
 * <h2>Multiplicadores por conservação</h2>
 * <p>O KBB não publica multiplicadores absolutos por estado de conservação
 * no JSON exposto — eles são aplicados client-side sobre {@code suggestedPrice}.
 * Para preservar a estrutura do contrato {@link VariacaoConservacaoKbb},
 * usamos os fatores padrão KBB configuráveis via env
 * ({@code GATEWAY_AVALIACAO_KBB_CONSERVACAO_*}). Esses valores são a
 * convenção pública KBB, não específicos por veículo — documentação técnica
 * no schema deixa isso claro para o consumidor da API.</p>
 *
 * <h2>Semântica de falha</h2>
 * <ul>
 *   <li><b>FlareSolverr desligado</b> + Jsoup direto bloqueado pelo Cloudflare
 *       → {@link PrecoKbbDTO#indisponivel(String, String, String)} graciosa.</li>
 *   <li><b>HTTP 5xx / FlareSolverr {@code status: error}</b> →
 *       {@link ResourceUnavailableException} bubbleado para o
 *       {@code kbbScraperCB}.</li>
 *   <li><b>Página carregou mas {@code vehiclePrices} não casou</b> →
 *       indisponibilidade graciosa (DOM/JSON mudaram).</li>
 *   <li><b>Veículo não mapeado no KBB</b> → indisponibilidade graciosa com
 *       mensagem explicativa.</li>
 * </ul>
 */
@Slf4j
@Component
public class KbbScraperClient implements KbbClientProvider {

    public static final String PROVIDER_NAME = "KBB";
    static final String FLARE_REQUIRED_MESSAGE =
            "FlareSolverr não está configurado — KBB depende do sidecar para resolver o desafio Cloudflare.";

    /** Pool determinístico de User-Agents — rotacionado por cursor atômico. */
    static final List<String> USER_AGENT_POOL = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; rv:121.0) Gecko/20100101 Firefox/121.0"
    );

    /**
     * Regex sobre o blob escapado dentro de {@code self.__next_f.push([1, "..."])}.
     * Captura o bloco {@code vehiclePrices\":{ ... }}. As aspas escapadas
     * (\") são preservadas porque o blob é JSON-em-string — desfazemos o
     * escape antes de parsear.
     */
    private static final Pattern VEHICLE_PRICES_PATTERN = Pattern.compile(
            "vehiclePrices\\\\\":\\{([^}]+)\\}");

    private static final Pattern PRICE_TYPE_PATTERN =
            Pattern.compile("priceType\\\\\":\\s*(\\d+)");
    private static final Pattern PRICE_RANGE_START_PATTERN =
            Pattern.compile("priceRangeStart\\\\\":\\s*([0-9.]+)");
    private static final Pattern PRICE_RANGE_END_PATTERN =
            Pattern.compile("priceRangeEnd\\\\\":\\s*([0-9.]+)");
    private static final Pattern KBB_PRICE_PATTERN =
            Pattern.compile("kbbPrice\\\\\":\\s*([0-9.]+)");
    private static final Pattern MILEAGE_QUERY_PATTERN =
            Pattern.compile("mileage(?:%3D|=)([0-9.,]+)");

    /** priceType=2 corresponde ao canal preço-de-troca (lojista). */
    private static final int PRICE_TYPE_TROCA = 2;
    /** priceType=4 corresponde ao canal preço-de-particular. */
    private static final int PRICE_TYPE_PARTICULAR = 4;

    private final AtomicInteger uaCursor = new AtomicInteger(0);

    private final String baseUrl;
    private final String searchPathTemplate;
    private final String defaultCategoria;
    private final BigDecimal conservacaoExcelente;
    private final BigDecimal conservacaoBom;
    private final BigDecimal conservacaoRegular;
    private final int kmPorAno;
    private final FlareSolverrInvoker flareSolverr;
    private final ExecutorService channelExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    public KbbScraperClient(
            @Value("${gateway.avaliacao.kbb.base-url:https://kbb.com.br}") String baseUrl,
            @Value("${gateway.avaliacao.kbb.search-path:/se/{ano}/{categoria}/{marca}/{modelo}/{versao}/{kbbId}/preco-de-{canal}/}") String searchPathTemplate,
            @Value("${gateway.avaliacao.kbb.categoria-default:carro}") String defaultCategoria,
            @Value("${gateway.avaliacao.kbb.conservacao.excelente:1.00}") BigDecimal conservacaoExcelente,
            @Value("${gateway.avaliacao.kbb.conservacao.bom:0.95}") BigDecimal conservacaoBom,
            @Value("${gateway.avaliacao.kbb.conservacao.regular:0.87}") BigDecimal conservacaoRegular,
            @Value("${gateway.avaliacao.kbb.km-por-ano:15000}") int kmPorAno,
            FlareSolverrInvoker flareSolverr) {
        this.baseUrl = baseUrl;
        this.searchPathTemplate = searchPathTemplate;
        this.defaultCategoria = defaultCategoria;
        this.conservacaoExcelente = conservacaoExcelente;
        this.conservacaoBom = conservacaoBom;
        this.conservacaoRegular = conservacaoRegular;
        this.kmPorAno = kmPorAno;
        this.flareSolverr = flareSolverr;
    }

    @Override
    @CircuitBreaker(name = "kbbScraperCB", fallbackMethod = "fallback")
    public PrecoKbbDTO fetchPreco(String codigoFipe, String marca, String modelo, int anoModelo) {
        String urlReferencia = buildSearchUrl(codigoFipe, marca, modelo, anoModelo);
        if (!flareSolverr.isEnabled()) {
            log.info("KBB: FlareSolverr desligado — devolvendo indisponível graciosamente.");
            return PrecoKbbDTO.indisponivel(codigoFipe, urlReferencia, FLARE_REQUIRED_MESSAGE);
        }

        String urlTroca = buildChannelUrl(marca, modelo, anoModelo, "troca");
        String urlParticular = buildChannelUrl(marca, modelo, anoModelo, "particular");

        CompletableFuture<ChannelResult> trocaFuture =
                CompletableFuture.supplyAsync(() -> safeFetchChannel(urlTroca, "troca"), channelExecutor);
        CompletableFuture<ChannelResult> particularFuture =
                CompletableFuture.supplyAsync(() -> safeFetchChannel(urlParticular, "particular"), channelExecutor);

        ChannelResult troca = trocaFuture.join();
        ChannelResult particular = particularFuture.join();

        if (troca.failed() && particular.failed()) {
            String motivo = troca.error() != null ? troca.error() : particular.error();
            if (motivo == null) motivo = "KBB não retornou vehiclePrices em nenhum dos dois canais.";
            log.warn("KBB: ambos canais falharam para {} ({} {}): {}", codigoFipe, marca, modelo, motivo);
            return PrecoKbbDTO.indisponivel(codigoFipe, urlReferencia,
                    "KBB indisponível para os dois canais (troca e particular). " + motivo);
        }

        VariacaoConservacaoKbb conservacao = new VariacaoConservacaoKbb(
                conservacaoExcelente, conservacaoBom, conservacaoRegular);
        Integer km = extractKmEstimada(troca, particular, anoModelo);

        log.info("KBB extraiu codigoFipe={} ano={} lojista={} particular={} km={} ua={}",
                codigoFipe, anoModelo, troca.faixa(), particular.faixa(), km, nextUserAgent());

        return new PrecoKbbDTO(codigoFipe, troca.faixa(), particular.faixa(),
                conservacao, km, true, urlReferencia, "");
    }

    @Override
    public String buildSearchUrl(String codigoFipe, String marca, String modelo, int anoModelo) {
        // URL "guarda-chuva" exposta no DTO para auditoria — aponta para o
        // canal /particular/ por convenção. As URLs efetivamente consultadas
        // (troca + particular) são construídas internamente por canal.
        return buildChannelUrl(marca, modelo, anoModelo, "particular");
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private PrecoKbbDTO fallback(String codigoFipe, String marca, String modelo, int anoModelo, Throwable cause) {
        String url = buildSearchUrl(codigoFipe, marca, modelo, anoModelo);
        log.warn("KBB fallback codigoFipe={} ano={} cause={}", codigoFipe, anoModelo, cause.toString());
        return PrecoKbbDTO.indisponivel(codigoFipe, url,
                "KBB indisponível ou Circuit Breaker aberto: " + cause.getClass().getSimpleName());
    }

    private String buildChannelUrl(String marca, String modelo, int anoModelo, String canal) {
        String path = searchPathTemplate
                .replace("{ano}", String.valueOf(anoModelo))
                .replace("{categoria}", defaultCategoria)
                .replace("{marca}", ScraperSupport.slugify(marca))
                .replace("{modelo}", ScraperSupport.slugify(modelo))
                .replace("{versao}", "")
                .replace("{kbbId}", "")
                .replace("{canal}", canal);
        // Colapsa segmentos vazios consecutivos (//) gerados quando versao/kbbId não foram resolvidos.
        path = path.replaceAll("/{2,}", "/");
        return baseUrl + path;
    }

    /**
     * Faz a chamada de um canal e converte para {@link ChannelResult}. Falhas
     * de infra (FlareSolverr, HTTP) viram resultado falho com mensagem — não
     * lançam, para que o {@code .join()} no fan-out interno nunca exploda.
     * Falhas de parse (vehiclePrices ausente) também viram falho.
     */
    private ChannelResult safeFetchChannel(String url, String canalLabel) {
        if (!flareSolverr.isEnabled()) {
            return ChannelResult.failed(canalLabel, FLARE_REQUIRED_MESSAGE);
        }
        String session = flareSolverr.createSession();
        try {
            FlareSolverrInvoker.FlareResult result = flareSolverr.getInSession(url, session);
            String html = result.html();
            if (html == null || html.isBlank()) {
                return ChannelResult.failed(canalLabel, "KBB retornou corpo vazio.");
            }
            return parseChannel(html, canalLabel);
        } catch (Exception ex) {
            return ChannelResult.failed(canalLabel,
                    "KBB falhou (" + ex.getClass().getSimpleName() + "): " + ex.getMessage());
        } finally {
            flareSolverr.destroySession(session);
        }
    }

    /**
     * Parse principal — extrai o bloco {@code vehiclePrices} embutido no HTML
     * via regex sobre o blob escapado dos scripts {@code __next_f.push}.
     * Devolve {@link FaixaPrecoKbb} com {@code (priceRangeStart, priceRangeEnd)}
     * convertidos para {@link BigDecimal} com escala 2.
     */
    private ChannelResult parseChannel(String html, String canalLabel) {
        Matcher block = VEHICLE_PRICES_PATTERN.matcher(html);
        if (!block.find()) {
            return ChannelResult.failed(canalLabel,
                    "Blob vehiclePrices ausente — provável página de redirecionamento ou veículo não mapeado.");
        }
        String slice = block.group(1);

        int priceType = parseInt(PRICE_TYPE_PATTERN, slice, -1);
        BigDecimal start = parseDecimal(PRICE_RANGE_START_PATTERN, slice);
        BigDecimal end = parseDecimal(PRICE_RANGE_END_PATTERN, slice);
        BigDecimal kbbPrice = parseDecimal(KBB_PRICE_PATTERN, slice);

        if (start == null && end == null && kbbPrice == null) {
            return ChannelResult.failed(canalLabel,
                    "vehiclePrices encontrado mas sem priceRangeStart/End nem kbbPrice — schema KBB pode ter mudado.");
        }
        if (start == null) start = kbbPrice;
        if (end == null) end = kbbPrice;
        FaixaPrecoKbb faixa = new FaixaPrecoKbb(round2(start), round2(end));
        Integer mileage = extractMileageFromHtml(html);
        return ChannelResult.ok(canalLabel, priceType, faixa, mileage);
    }

    /** Quilometragem-base do KBB: lida do query param {@code mileage} embutido no HTML, com fallback computacional. */
    private Integer extractKmEstimada(ChannelResult troca, ChannelResult particular, int anoModelo) {
        if (troca.mileage() != null) return troca.mileage();
        if (particular.mileage() != null) return particular.mileage();
        int idade = Math.max(0, Year.now().getValue() - anoModelo);
        return idade * kmPorAno;
    }

    private Integer extractMileageFromHtml(String html) {
        Matcher m = MILEAGE_QUERY_PATTERN.matcher(html);
        if (!m.find()) return null;
        String numeric = m.group(1).replace(".", "").replace(",", "");
        try {
            return Integer.parseInt(numeric);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int parseInt(Pattern pattern, String src, int defaultValue) {
        Matcher m = pattern.matcher(src);
        if (!m.find()) return defaultValue;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ex) { return defaultValue; }
    }

    private static BigDecimal parseDecimal(Pattern pattern, String src) {
        Matcher m = pattern.matcher(src);
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal round2(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Próximo User-Agent do pool — cursor atômico evita corrida entre threads
     * concorrentes. Hoje é usado apenas em log para auditoria; ganha efeito
     * real quando o pipeline ganhar um modo Jsoup direto sem FlareSolverr.
     */
    String nextUserAgent() {
        int next = uaCursor.getAndIncrement();
        return USER_AGENT_POOL.get(Math.floorMod(next, USER_AGENT_POOL.size()));
    }

    /**
     * Resultado da raspagem de um canal único (troca ou particular). Carrega
     * a faixa quando bem-sucedido ou a mensagem de erro para diagnóstico.
     * {@code mileage} sobe junto porque ambos canais podem expor o mesmo
     * número — o primeiro que casar vence no merge.
     */
    private record ChannelResult(
            String canal,
            int priceType,
            FaixaPrecoKbb faixa,
            Integer mileage,
            boolean failed,
            String error) {

        static ChannelResult ok(String canal, int priceType, FaixaPrecoKbb faixa, Integer mileage) {
            return new ChannelResult(canal, priceType, faixa, mileage, false, null);
        }

        static ChannelResult failed(String canal, String error) {
            return new ChannelResult(canal, -1, null, null, true, error);
        }
    }

    // Mantida para análise futura de coleta de mileage com tolerância maior.
    @SuppressWarnings("unused")
    private static List<Integer> sweep(Pattern pattern, String src) {
        List<Integer> out = new ArrayList<>();
        Matcher m = pattern.matcher(src);
        while (m.find()) {
            try { out.add(Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}
