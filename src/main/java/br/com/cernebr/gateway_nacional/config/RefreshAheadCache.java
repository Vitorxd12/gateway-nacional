package br.com.cernebr.gateway_nacional.config;

import br.com.cernebr.gateway_nacional.config.CacheConfig.CachedEntry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Implementa o padrão soft-TTL / hard-TTL (refresh-ahead) sobre o Spring Cache.
 *
 * <p><b>Por que existe:</b> {@code @Cacheable} cobre o caminho hit/miss, mas
 * sofre cache stampede na borda do TTL — quando uma chave popular expira,
 * todas as requisições concorrentes batem no provider de uma vez. Aqui o
 * "hard TTL" do Redis se mantém (configurado por cache no
 * {@link CacheConfig#cacheManager}), e um "soft TTL" embarcado no
 * {@link CachedEntry#cachedAt} dispara refresh assíncrono <em>antes</em> da
 * expiração real. Latência percebida fica constante; quem está atravessando
 * o soft-TTL serve o valor velho enquanto o background recarrega.</p>
 *
 * <p><b>Janelas:</b></p>
 * <ul>
 *   <li><b>0 → soft TTL:</b> serve cached, sem disparar nada.</li>
 *   <li><b>soft TTL → hard TTL:</b> serve cached, dispara refresh em
 *       background (uma única vez por chave por JVM, ver dedup abaixo).</li>
 *   <li><b>&gt; hard TTL:</b> Redis já descartou — vira cache miss síncrono.</li>
 * </ul>
 *
 * <p><b>Dedup intra-JVM:</b> um {@link ConcurrentMap} guarda chaves em
 * refresh ativo. Refreshes concorrentes para a mesma chave são ignorados
 * silenciosamente. Em multi-pod, pods diferentes podem refresh em paralelo —
 * ruído aceitável (o vencedor sobrescreve, idempotente).</p>
 *
 * <p><b>Tolerância a falha do Redis:</b> erros de leitura ou escrita do cache
 * <em>não</em> propagam para o caller — o loader é executado direto e a
 * resposta segue. Alinha com a postura defensiva do
 * {@code ResilientGenericJacksonSerializer}: cache nunca degrada o caminho
 * crítico da request.</p>
 *
 * <p><b>Quando NÃO usar:</b> caches com TTL muito curto (ex.: {@code cambio}
 * com hard de 3min), em que soft-TTL não cabe entre o write e a próxima
 * cobrança de quota. Para esses, o {@code @Cacheable} simples segue ideal.</p>
 */
@Slf4j
@Component
public class RefreshAheadCache {

    private final CacheManager cacheManager;
    private final ExecutorService refreshExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public RefreshAheadCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdown();
    }

    /**
     * Serve do cache se houver entry válido (mesmo que stale, dentro do hard TTL).
     * Em stale, dispara refresh assíncrono e retorna o valor velho. Em miss,
     * executa o {@code loader} sincronamente e popula o cache.
     *
     * @param cacheName nome do cache configurado no {@link CacheManager}
     * @param key       chave dentro do cache
     * @param softTtl   janela após {@link CachedEntry#cachedAt} além da qual
     *                  o entry vira "stale" e elege refresh; deve ser
     *                  estritamente menor que o hard TTL configurado para o
     *                  cache (ex.: ceps soft 7d / hard 30d)
     * @param loader    operação que produz o valor fresco — tipicamente
     *                  encapsula o {@link HedgedExecutor#anyOf(String, java.util.List)}
     */
    public <T> T get(String cacheName, Object key, Duration softTtl, Supplier<T> loader) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("Cache '{}' não configurado — degradando para chamada direta.", cacheName);
            return loader.get();
        }

        CachedEntry<T> entry = readEntry(cache, key);

        if (entry == null) {
            T fresh = loader.get();
            putSafe(cache, key, fresh);
            return fresh;
        }

        if (entry.isStale(softTtl)) {
            triggerRefresh(cache, cacheName, key, loader);
        }
        return entry.value();
    }

    /**
     * Leitura defensiva: trata como miss tanto a ausência de chave quanto a
     * presença de um valor em formato antigo (ex.: serviços que ainda usam
     * {@code @Cacheable} sem wrapper) ou entries que não passaram pelo round-trip
     * Jackson com sucesso. Isso permite migração incremental por service —
     * coexiste com o pattern {@code @Cacheable} simples.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private <T> CachedEntry<T> readEntry(Cache cache, Object key) {
        Cache.ValueWrapper wrapper;
        try {
            wrapper = cache.get(key);
        } catch (Exception ex) {
            log.warn("Cache read failed for key={}: {} — tratando como miss.", key, ex.toString());
            return null;
        }
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value instanceof CachedEntry<?> cachedEntry) {
            return (CachedEntry<T>) cachedEntry;
        }
        return null;
    }

    private <T> void putSafe(Cache cache, Object key, T value) {
        try {
            cache.put(key, CachedEntry.of(value));
        } catch (Exception ex) {
            log.warn("Cache write failed for key={}: {} — resposta segue sem cache.", key, ex.toString());
        }
    }

    private <T> void triggerRefresh(Cache cache, String cacheName, Object key, Supplier<T> loader) {
        String lockKey = cacheName + ":" + key;
        if (inFlight.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            return;
        }
        refreshExecutor.submit(() -> {
            try {
                T fresh = loader.get();
                putSafe(cache, key, fresh);
                log.debug("Refresh-ahead atualizou {}", lockKey);
            } catch (Exception ex) {
                log.warn("Refresh-ahead falhou para {}: {}", lockKey, ex.toString());
            } finally {
                inFlight.remove(lockKey);
            }
        });
    }
}
