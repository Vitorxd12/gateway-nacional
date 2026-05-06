package br.com.cernebr.gateway_nacional.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Distributed rate-limiting infrastructure backed by Bucket4j on Redis.
 *
 * <p>Uses an isolated {@link RedisClient} (rather than reusing Spring's
 * {@code LettuceConnectionFactory}) so that Bucket4j's connection lifecycle
 * is decoupled from the cache layer — failures or shutdown sequences in one
 * cannot cascade into the other.</p>
 *
 * <p>The bucket configuration is exposed as a {@link Supplier} so that
 * Bucket4j can lazily evaluate it the first time a given IP is seen,
 * avoiding eager allocation for buckets that may never be created.</p>
 */
@Configuration
public class RateLimitConfig {

    /**
     * Public-tier policy: 5 requests per IP per minute, shared across all
     * {@code /api/v1/**} endpoints. Tightening this further is meaningful
     * only with proper IP attribution behind a trusted reverse proxy.
     */
    private static final long PUBLIC_TIER_CAPACITY = 5L;
    private static final Duration PUBLIC_TIER_PERIOD = Duration.ofMinutes(1);

    /**
     * Slightly longer than the refill period — a fully-empty bucket can be
     * forgotten from Redis once its data is no longer relevant.
     */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(2);

    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(DataRedisProperties properties,
                                           @Value("${spring.data.redis.host:localhost}") String host,
                                           @Value("${spring.data.redis.port:6379}") int port) {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(properties.getHost() != null ? properties.getHost() : host)
                .withPort(properties.getPort() > 0 ? properties.getPort() : port)
                .withDatabase(properties.getDatabase());

        if (properties.getUsername() != null && properties.getPassword() != null) {
            uri.withAuthentication(properties.getUsername(), properties.getPassword().toCharArray());
        } else if (properties.getPassword() != null) {
            uri.withPassword(properties.getPassword().toCharArray());
        }

        return RedisClient.create(uri.build());
    }

    @Bean
    public ProxyManager<byte[]> rateLimiterProxyManager(RedisClient bucket4jRedisClient) {
        return LettuceBasedProxyManager.builderFor(bucket4jRedisClient)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.fixedTimeToLive(BUCKET_TTL))
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> publicTierBucketConfiguration() {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(PUBLIC_TIER_CAPACITY, PUBLIC_TIER_PERIOD))
                .build();
    }
}
