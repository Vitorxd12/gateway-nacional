package br.com.cernebr.gateway_nacional.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.serializer.SerializationException;
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
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Duration CEPS_TTL = Duration.ofDays(30);
    private static final Duration FERIADOS_TTL = Duration.ofDays(365);
    private static final Duration TAXAS_TTL = Duration.ofHours(12);
    private static final Duration RASTREIOS_TTL = Duration.ofHours(1);
    private static final Duration BANCOS_TTL = Duration.ofDays(30);
    private static final Duration FIPE_TTL = Duration.ofDays(15);
    private static final Duration PLACAS_TTL = Duration.ofDays(365);
    private static final Duration SAUDE_TTL = Duration.ofDays(15);
    // NCM (Nomenclatura Comum do Mercosul) — a tabela é atualizada algumas
    // vezes por ano via resoluções da Camex; 30 dias é o ponto de equilíbrio
    // entre virtualmente zerar tráfego upstream e não atrasar a propagação
    // de uma resolução nova além de uma janela aceitável.
    private static final Duration NCM_TTL = Duration.ofDays(30);
    // CNAE (CONCLA) — atualizada raras vezes por ano; mesmo TTL da NCM.
    private static final Duration CNAE_TTL = Duration.ofDays(30);

    private static final String CEPS_CACHE = "ceps";
    private static final String FERIADOS_CACHE = "feriados";
    private static final String TAXAS_CACHE = "taxas";
    private static final String RASTREIOS_CACHE = "rastreios";
    private static final String BANCOS_CACHE = "bancos";
    private static final String FIPE_CACHE = "fipe";
    private static final String PLACAS_CACHE = "placas";
    private static final String SAUDE_CACHE = "saude";
    private static final String NCM_CACHE = "ncm";
    private static final String CNAE_CACHE = "cnae";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = baseConfig().entryTtl(DEFAULT_TTL);

        Map<String, RedisCacheConfiguration> perCacheConfigs = Map.of(
                CEPS_CACHE, baseConfig().entryTtl(CEPS_TTL),
                FERIADOS_CACHE, baseConfig().entryTtl(FERIADOS_TTL),
                TAXAS_CACHE, baseConfig().entryTtl(TAXAS_TTL),
                RASTREIOS_CACHE, baseConfig().entryTtl(RASTREIOS_TTL),
                BANCOS_CACHE, baseConfig().entryTtl(BANCOS_TTL),
                FIPE_CACHE, baseConfig().entryTtl(FIPE_TTL),
                PLACAS_CACHE, baseConfig().entryTtl(PLACAS_TTL),
                SAUDE_CACHE, baseConfig().entryTtl(SAUDE_TTL),
                NCM_CACHE, baseConfig().entryTtl(NCM_TTL),
                CNAE_CACHE, baseConfig().entryTtl(CNAE_TTL)
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
                        .fromSerializer(redisValueSerializer()));
    }

    /**
     * Spring Data Redis 4.0.x ships {@link GenericJackson2JsonRedisSerializer}
     * which still uses Jackson 2 internally (independent of the Jackson 3 our
     * HTTP layer uses).
     *
     * <p>Two requirements are layered here:
     * <ul>
     *   <li><b>Default polymorphic typing</b> — required so {@link Object}-typed
     *       reads round-trip back to the original record class. The no-arg
     *       constructor wires it up with the SDR-canonical configuration;</li>
     *   <li><b>JSR-310 module</b> — required for any DTO with {@link java.time.LocalDate}
     *       / {@link java.time.LocalDateTime} (FeriadoResponse, TaxaResponse,
     *       RastreioResponse). Without it the first cache write throws
     *       {@code InvalidDefinitionException: "java.time.LocalDate" not supported}.</li>
     * </ul>
     *
     * <p>{@code .configure(Consumer<ObjectMapper>)} mutates the serializer's
     * internal mapper post-construction — preserves the default typing the
     * no-arg constructor set up, while registering the missing module.</p>
     */
    private static GenericJackson2JsonRedisSerializer redisValueSerializer() {
        // Wraps SDR's default serializer with two upgrades:
        // 1. {@code JavaTimeModule} so LocalDate/LocalDateTime serialize at all;
        // 2. {@code WRITE_DATES_AS_TIMESTAMPS=false} so they go as ISO strings,
        //    aligning with the default-typing's {@code VALUE_STRING} expectation
        //    for the type-id property.
        // Per-cache typing for List/array roots is a known gap of SDR's
        // default-typing strategy. The {@link ResilientGenericJacksonSerializer}
        // override below treats deserialize failures as cache misses, so a
        // cache-bypass on List roots stays operational instead of throwing 500.
        return new ResilientGenericJacksonSerializer()
                .configure(mapper -> {
                    mapper.registerModule(new JavaTimeModule());
                    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                });
    }

    /**
     * Subclass that demotes a deserialization failure to a cache miss.
     *
     * <p>Spring Data Redis 4.0.x's {@code GenericJackson2JsonRedisSerializer}
     * with default typing trips on {@code List<Record>} cache values — the
     * array root has no {@code @class} marker, so the read-back blows up
     * with {@code Unexpected token (START_OBJECT), expected VALUE_STRING}.
     * Returning {@code null} here makes the {@code @Cacheable} layer treat
     * the entry as missing; the service recomputes (cheap on a warm
     * downstream cache) and overwrites with the same broken format. End user
     * sees a 200, never the 500.</p>
     *
     * <p>This is a pragmatic patch for a gap in SDR's serializer stack —
     * remove when SDR ships {@code DefaultTyping.NON_CONCRETE_AND_ARRAYS}
     * support out of the box.</p>
     */
    private static final class ResilientGenericJacksonSerializer extends GenericJackson2JsonRedisSerializer {
        @Override
        public Object deserialize(byte[] source) throws SerializationException {
            try {
                return super.deserialize(source);
            } catch (Exception ex) {
                log.debug("Redis cache deserialize failed, treating as miss: {}", ex.getMessage());
                return null;
            }
        }
    }
}
