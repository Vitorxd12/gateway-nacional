package br.com.cernebr.gateway_nacional.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fallback de cache para o profile {@code no-redis} — entrega um
 * {@link ConcurrentMapCacheManager} em memória. Substitui o
 * {@code CacheConfig} (que depende de Redis) em ambientes de validação
 * local sem sidecar.
 *
 * <p>É o profile usado pelo desenvolvedor que precisa rodar
 * {@code mvn spring-boot:run} contra a rota de avaliação sem subir Redis.
 * O {@code @Cacheable} continua funcional via cache local; o
 * {@link RefreshAheadCache} é satisfeito porque ele depende somente do
 * contrato {@link CacheManager}, não da implementação Redis.</p>
 */
@Configuration
@EnableCaching
@Profile("no-redis")
public class NoRedisCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        // Cache em memória, sem TTL — adequado apenas para validação local.
        // O cache manager não cria caches estaticamente; eles são instanciados
        // sob demanda no primeiro @Cacheable, mantendo o comportamento das
        // chaves originalmente configuradas em RedisCacheManager.
        return new ConcurrentMapCacheManager();
    }
}
