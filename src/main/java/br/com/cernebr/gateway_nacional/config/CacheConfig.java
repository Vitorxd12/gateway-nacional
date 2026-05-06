package br.com.cernebr.gateway_nacional.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Caching infrastructure backed by Redis.
 *
 * <p>Default TTL of 24h applies to any cache that is not explicitly configured.
 * The {@code ceps} cache uses an extended TTL of 30 days because Brazilian
 * postal codes are virtually immutable and we want to minimize upstream load.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Duration CEPS_TTL = Duration.ofDays(30);
    private static final Duration FERIADOS_TTL = Duration.ofDays(365);

    private static final String CEPS_CACHE = "ceps";
    private static final String FERIADOS_CACHE = "feriados";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = baseConfig().entryTtl(DEFAULT_TTL);

        Map<String, RedisCacheConfiguration> perCacheConfigs = Map.of(
                CEPS_CACHE, baseConfig().entryTtl(CEPS_TTL),
                FERIADOS_CACHE, baseConfig().entryTtl(FERIADOS_TTL)
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(perCacheConfigs)
                .build();
    }

    private RedisCacheConfiguration baseConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
    }
}
