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
import java.time.Instant;
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
    // Câmbio — cotações reais flutuam a cada segundo, mas absorver 100%
    // do tráfego mata o quota da AwesomeAPI. 3 minutos é o equilíbrio:
    // suficiente para colapsar bursts de dashboards/ERPs em uma única
    // requisição upstream, curto o bastante para não estagnar a cotação.
    private static final Duration CAMBIO_TTL = Duration.ofMinutes(3);
    // Sanções (CGU/CEIS) — publicações no portal entram com semanas de
    // delay frente ao DOU; 7 dias absorve a janela típica sem servir
    // dados antigos demais para devida diligência de compras.
    private static final Duration SANCOES_TTL = Duration.ofDays(7);
    // Processos (DataJud) — tribunais publicam movimentos em batches D+1;
    // 24h cobre o ciclo de atualização sem desperdiçar Redis com keys
    // que mudam a cada 30 minutos.
    private static final Duration PROCESSOS_TTL = Duration.ofHours(24);
    // Indicadores APS (Previne Brasil/PMA) — uma vez consolidada a nota
    // de um quadrimestre, ela é frozen pelo Ministério da Saúde até a
    // próxima portaria. 30 dias dá folga para refresh sem refletir um
    // valor antigo após eventual reabertura administrativa de quadrimestre.
    private static final Duration INDICADORES_APS_TTL = Duration.ofDays(30);

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
    private static final String CAMBIO_CACHE = "cambio";
    private static final String SANCOES_CACHE = "sancoes";
    private static final String PROCESSOS_CACHE = "processos";
    private static final String INDICADORES_APS_CACHE = "indicadoresAps";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = baseConfig().entryTtl(DEFAULT_TTL);

        Map<String, RedisCacheConfiguration> perCacheConfigs = Map.ofEntries(
                Map.entry(CEPS_CACHE, baseConfig().entryTtl(CEPS_TTL)),
                Map.entry(FERIADOS_CACHE, baseConfig().entryTtl(FERIADOS_TTL)),
                Map.entry(TAXAS_CACHE, baseConfig().entryTtl(TAXAS_TTL)),
                Map.entry(RASTREIOS_CACHE, baseConfig().entryTtl(RASTREIOS_TTL)),
                Map.entry(BANCOS_CACHE, baseConfig().entryTtl(BANCOS_TTL)),
                Map.entry(FIPE_CACHE, baseConfig().entryTtl(FIPE_TTL)),
                Map.entry(PLACAS_CACHE, baseConfig().entryTtl(PLACAS_TTL)),
                Map.entry(SAUDE_CACHE, baseConfig().entryTtl(SAUDE_TTL)),
                Map.entry(NCM_CACHE, baseConfig().entryTtl(NCM_TTL)),
                Map.entry(CNAE_CACHE, baseConfig().entryTtl(CNAE_TTL)),
                Map.entry(CAMBIO_CACHE, baseConfig().entryTtl(CAMBIO_TTL)),
                Map.entry(SANCOES_CACHE, baseConfig().entryTtl(SANCOES_TTL)),
                Map.entry(PROCESSOS_CACHE, baseConfig().entryTtl(PROCESSOS_TTL)),
                Map.entry(INDICADORES_APS_CACHE, baseConfig().entryTtl(INDICADORES_APS_TTL))
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

    /**
     * Envelope opt-in para o padrão soft-TTL / hard-TTL (refresh-ahead).
     *
     * <p>Quem grava no Redis usa o TTL configurado por cache nome ({@code hard TTL})
     * — esse é o limite a partir do qual a key é descartada. O {@code soft TTL} vive
     * fora do Redis: é uma duração arbitrária, definida no service, comparada contra
     * {@link #cachedAt} para decidir se o valor ainda é "fresco" ou se vale acionar
     * um refresh assíncrono enquanto serve o valor velho.</p>
     *
     * <p><b>Round-trip Jackson:</b> a {@link #redisValueSerializer() serialização}
     * já configurada com default typing ativo materializa o wrapper como
     * {@code ["...CachedEntry", {"value": ["...DtoConcreto", {…}], "cachedAt": "ISO-8601"}]}.
     * O type-id no payload garante que o read-back reconstrua o {@code value}
     * polimorficamente sem perda de tipo, e o {@link Instant} usa o formato ISO
     * porque o {@code JavaTimeModule} desliga {@code WRITE_DATES_AS_TIMESTAMPS}.</p>
     *
     * <p><b>Quando é seguro embrulhar:</b> use para DTOs concretos
     * (record/POJO). <b>Não</b> embrulhe coleções diretamente (ex.:
     * {@code CachedEntry<List<X>>}) — o root array da lista cai na mesma
     * lacuna de default typing tratada pelo {@link ResilientGenericJacksonSerializer},
     * e o cache vai degradar para miss permanente. Se precisar cachear lista,
     * embrulhe-a primeiro num DTO ({@code record Page<T>(List<T> items, …)}).</p>
     *
     * <p><b>Compatibilidade:</b> caches que continuam usando {@code @Cacheable}
     * sem wrapper não são afetados — a presença do envelope é decidida pelo
     * caller. Os dois formatos coexistem na mesma instância Redis.</p>
     */
    public record CachedEntry<T>(T value, Instant cachedAt) {

        public static <T> CachedEntry<T> of(T value) {
            return new CachedEntry<>(value, Instant.now());
        }

        /**
         * {@code true} quando o valor passou do soft-TTL e está apto a refresh
         * em background; ainda servível ao chamador atual.
         */
        public boolean isStale(Duration softTtl) {
            return Duration.between(cachedAt, Instant.now()).compareTo(softTtl) > 0;
        }
    }
}
