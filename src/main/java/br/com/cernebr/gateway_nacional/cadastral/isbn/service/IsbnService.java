package br.com.cernebr.gateway_nacional.cadastral.isbn.service;

import br.com.cernebr.gateway_nacional.cadastral.isbn.client.BrasilApiIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.CblIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.GoogleBooksIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.IsbnClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.MercadoEditorialIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.OpenLibraryIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Resolve um ISBN combinando dois padrões já estabelecidos no projeto:
 * <ol>
 *   <li>{@link RefreshAheadCache} aplica soft-TTL/hard-TTL: o cache "isbns"
 *       tem hard-TTL de 365 dias (livros são essencialmente imutáveis após
 *       publicação); o soft-TTL de 90 dias dispara refresh assíncrono em
 *       background — útil para o caso raro em que um provider corrige
 *       metadados (capa, sinopse) depois da primeira leitura.</li>
 *   <li>{@link HedgedExecutor} dispara os providers em paralelo (até 5):
 *       <b>BrasilAPI (primário) + CBL + Google Books + Mercado Editorial +
 *       Open Library</b>. Vence o primeiro com sucesso.</li>
 * </ol>
 *
 * <h2>Por que BrasilAPI primário (RULE B)</h2>
 * <p>A própria BrasilAPI já agrega CBL/Google Books/Mercado Editorial/Open
 * Library internamente — quando ela está saudável, costuma vencer o hedge
 * por já ter feito a consolidação. Mantemos os 4 originais isolados como
 * rede de proteção: se a BrasilAPI cair, qualquer um dos 4 pode resolver.</p>
 *
 * <h2>Soft-TTL alto (90 dias)</h2>
 * <p>Diferente de CEP/CNPJ (dados que podem mudar), uma vez resolvido um
 * ISBN, os metadados não mudam — só correções pontuais de catalogação.
 * 90 dias entrega refresh oportunista sem desperdício para chaves frias
 * (livros raramente acessados não merecem recargas mensais).</p>
 *
 * <h2>Seleção de providers via {@code ?providers=}</h2>
 * <p>Aceita aliases lowercase com hífen, idênticos aos da BrasilAPI:
 * {@code brasilapi}, {@code cbl}, {@code google-books}, {@code mercado-editorial},
 * {@code open-library}. Quando informado, o hedge dispara apenas o subset.
 * Um alias desconhecido é silenciosamente ignorado; se nenhum alias for válido,
 * o serviço cai de volta para os 5 (espelha o comportamento da BrasilAPI).
 * A chave do cache inclui os providers ordenados — uma seleção explícita
 * gera entrada Redis distinta da seleção "todos".</p>
 *
 * <p>Métricas de provider são emitidas pelo {@link HedgedExecutor}; este
 * service não duplica instrumentação.</p>
 */
@Slf4j
@Service
public class IsbnService {

    private static final String DOMAIN = "isbn";
    private static final String CACHE_NAME = "isbns";
    private static final Duration SOFT_TTL = Duration.ofDays(90);

    /**
     * Aliases públicos → instâncias dos clients. {@code LinkedHashMap} mantém
     * a ordem de declaração estável (BrasilAPI primário primeiro), o que
     * dá uma ordem determinística para a chave de cache derivada.
     */
    private final Map<String, IsbnClientProvider> providersByAlias;
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public IsbnService(BrasilApiIsbnClient brasilApi,
                       CblIsbnClient cbl,
                       GoogleBooksIsbnClient googleBooks,
                       MercadoEditorialIsbnClient mercadoEditorial,
                       OpenLibraryIsbnClient openLibrary,
                       HedgedExecutor hedgedExecutor,
                       RefreshAheadCache refreshAheadCache) {
        Map<String, IsbnClientProvider> map = new LinkedHashMap<>(5);
        map.put("brasilapi", brasilApi);
        map.put("cbl", cbl);
        map.put("google-books", googleBooks);
        map.put("mercado-editorial", mercadoEditorial);
        map.put("open-library", openLibrary);
        this.providersByAlias = Map.copyOf(map);
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    /**
     * Conjunto de aliases públicos suportados — exposto para o controller
     * documentar erros de input com a lista canônica.
     */
    public Set<String> supportedProviders() {
        return providersByAlias.keySet();
    }

    public IsbnResponse findByIsbn(String normalizedIsbn) {
        return findByIsbn(normalizedIsbn, null);
    }

    /**
     * @param requestedAliases conjunto de aliases solicitado via {@code ?providers=}
     *                         ({@code null} ou vazio → usa todos os 5)
     */
    public IsbnResponse findByIsbn(String normalizedIsbn, @Nullable Set<String> requestedAliases) {
        List<Map.Entry<String, IsbnClientProvider>> selected = resolveSelection(requestedAliases);
        String cacheKey = buildCacheKey(normalizedIsbn, selected);
        return refreshAheadCache.get(CACHE_NAME, cacheKey, SOFT_TTL,
                () -> loadFromProviders(normalizedIsbn, selected));
    }

    /**
     * Filtra os providers pelos aliases pedidos. Aliases desconhecidos são
     * descartados silenciosamente; se a lista filtrada ficar vazia,
     * volta-se para todos os providers (comportamento da BrasilAPI).
     */
    private List<Map.Entry<String, IsbnClientProvider>> resolveSelection(@Nullable Set<String> requestedAliases) {
        if (requestedAliases == null || requestedAliases.isEmpty()) {
            return List.copyOf(providersByAlias.entrySet());
        }
        List<Map.Entry<String, IsbnClientProvider>> filtered = new ArrayList<>(requestedAliases.size());
        for (Map.Entry<String, IsbnClientProvider> entry : providersByAlias.entrySet()) {
            if (requestedAliases.contains(entry.getKey())) {
                filtered.add(entry);
            }
        }
        return filtered.isEmpty() ? List.copyOf(providersByAlias.entrySet()) : filtered;
    }

    /**
     * Chave de cache: ISBN puro quando todos os providers estão selecionados;
     * {@code ISBN::alias1,alias2} quando há subset. Aliases ordenados (TreeSet)
     * para que {@code "cbl,google-books"} e {@code "google-books,cbl"}
     * compartilhem entrada Redis.
     */
    private String buildCacheKey(String isbn, List<Map.Entry<String, IsbnClientProvider>> selected) {
        if (selected.size() == providersByAlias.size()) {
            return isbn;
        }
        Set<String> sorted = new TreeSet<>();
        for (var entry : selected) sorted.add(entry.getKey());
        return isbn + "::" + String.join(",", sorted);
    }

    /**
     * Parser do query param {@code providers=cbl,google-books}. Pública pra
     * facilitar uso por controllers — descarta entradas vazias e normaliza
     * para lowercase. {@code null} ou string em branco → {@code null}
     * (= "usa todos").
     */
    @Nullable
    public static Set<String> parseRequestedProviders(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<String> aliases = Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return aliases.isEmpty() ? null : aliases;
    }

    private IsbnResponse loadFromProviders(String isbn, List<Map.Entry<String, IsbnClientProvider>> selected) {
        List<NamedSupplier<IsbnResponse>> hedgeTargets = selected.stream()
                .map(entry -> new NamedSupplier<>(entry.getValue().providerName(),
                        (java.util.function.Supplier<IsbnResponse>) () -> entry.getValue().fetch(isbn)))
                .toList();

        try {
            return hedgedExecutor.anyOf(DOMAIN, hedgeTargets);
        } catch (ResourceUnavailableException ex) {
            // Quando todos os providers exauriram, distinguimos:
            //   - "não localizado/encontrado" no cause → ISBN não existe → 404
            //   - "indisponível/Circuit Breaker aberto" → infra → 503
            // Os 5 clients seguem essa convenção textual estrita; auditar
            // qualquer client novo para manter a mesma linguagem.
            if (lastCauseSignalsNotFound(ex)) {
                throw new ResourceNotFoundException("ISBN",
                        "ISBN " + isbn + " não localizado em nenhum provider.");
            }
            throw ex;
        }
    }

    private static boolean lastCauseSignalsNotFound(Throwable ex) {
        Throwable cur = ex.getCause();
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("não localizado") || lower.contains("não encontrado")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
