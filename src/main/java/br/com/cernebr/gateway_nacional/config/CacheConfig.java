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
    // PTAX histórico — fixings de datas passadas são, por definição, imutáveis.
    // Uma vez publicado, o BCB não revisa o boletim "Fechamento PTAX" daquela
    // data. TTL longo (365d) elimina ~100% do tráfego upstream para relatórios
    // recorrentes (IR mensal, balanço trimestral, retroativos de NF-e).
    private static final Duration CAMBIO_HISTORICO_TTL = Duration.ofDays(365);
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
    // ISBNs — uma vez publicado, o livro é "frozen": metadados (título, autor,
    // capa, ano de publicação) não mudam. Correções pontuais de catalogação
    // existem mas são raras. 365d é o teto razoável para matar tráfego upstream
    // sem degradar a percepção de frescor; o RAC com soft-TTL de 90d garante
    // refresh oportunista para títulos quentes.
    private static final Duration ISBNS_TTL = Duration.ofDays(365);
    // PIX participantes — o BCB regenera o CSV em todo dia útil bancário; 24h
    // de hard-TTL casa com a cadência de publicação. Soft-TTL de 6h via RAC
    // dispara refresh oportunista (cobre o cenário "li às 8h, BCB publicou
    // novo às 14h, próximo cliente após 14h pega versão fresca em background").
    private static final Duration PIX_PARTICIPANTES_TTL = Duration.ofHours(24);
    // Catálogo de moedas PTAX (BCB /Moedas) — o BCB altera o conjunto raras
    // vezes por ano, e cada chamada de câmbio consulta esse cache para validar
    // pares antes de bater no PTAX. 30d garante zero round-trip a /Moedas no
    // caminho crítico; o fallback embutido cobre o caso raro de cache miss
    // simultâneo a indisponibilidade do BCB.
    private static final Duration PTAX_CATALOG_TTL = Duration.ofDays(30);
    // IBGE — UFs e municípios são federal-fixos (último município criado em
    // 2013, último estado em 1988). 365d garante zero round-trip ao IBGE no
    // hot path; soft-TTL de 30d via RAC absorve a alteração rara que possa
    // entrar via portaria do IBGE.
    private static final Duration IBGE_TTL = Duration.ofDays(365);
    // CVM — snapshots completos (corretoras ~150 itens, fundos ~30k itens)
    // baixados do dados.cvm.gov.br. Publicação mensal. 30d hard absorve o
    // ciclo; 7d soft via RAC dispara refresh entre janelas.
    private static final Duration CVM_TTL = Duration.ofDays(30);
    // B3 — listagens de tickers (ações + fundos). A B3 atualiza a base de
    // listadas semanalmente (admissões/exclusões); 30d hard absorve o ciclo,
    // 7d soft via RAC dispara refresh oportunista entre janelas.
    private static final Duration B3_TTL = Duration.ofDays(30);
    // DDD — quadro nacional ANATEL muda raras vezes por década. 365d hard
    // elimina round-trip à ANATEL no hot path; 90d soft via RAC permite
    // refresh oportunista para chaves quentes (DDDs metropolitanos).
    private static final Duration DDD_TTL = Duration.ofDays(365);
    // CPTEC — meteorologia. Previsão de até 6 dias muda lentamente; 1h
    // hard absorve bursts de dashboards agro/logístico sem servir nada
    // estranho ao usuário final.
    private static final Duration CPTEC_TTL = Duration.ofHours(1);
    // Registro.br — disponibilidade de domínio. Resultado é "snapshot": um
    // domínio liberado pode ser registrado em segundos por terceiros, então
    // 10min absorve consultas repetidas do mesmo dashboard sem mascarar
    // movimentações reais de registro.
    private static final Duration REGISTRO_BR_TTL = Duration.ofMinutes(10);
    // TUSS — terminologia ANS. A ANS publica revisões com cadência mensal
    // a trimestral; 7d hard cobre o ciclo médio sem desperdiçar Redis em
    // consultas a códigos individuais.
    private static final Duration TUSS_TTL = Duration.ofDays(7);
    // Licitações — listagem agregada (PNCP/ComprasNet, BLL, BNC, Licitanet).
    // 12h hard cobre o ciclo de publicação típico do PNCP (refreshes em
    // janelas de 6-12h); soft-TTL 30m via RAC dispara refresh oportunista
    // entre ondas matinais e vespertinas sem martelar os portais frágeis.
    private static final Duration LICITACOES_ATIVAS_TTL = Duration.ofHours(12);
    // Licitações — detalhe de um edital. Anexos e itens são estáveis após
    // publicação; 12h hard sobra. Soft-TTL 2h via RAC absorve correções
    // pontuais (republicação de edital com errata).
    private static final Duration LICITACOES_DETALHE_TTL = Duration.ofHours(12);
    // Simples Nacional — estado de enquadramento muda em janelas mensais
    // (adesão/exclusão). 24h hard absorve consultas batch de ERPs (folha,
    // emissor de NF-e). Soft-TTL 12h via RAC dispara refresh sem martelar
    // o portal Consulta Optantes — que tem CAPTCHA em horário comercial.
    private static final Duration SIMPLES_TTL = Duration.ofHours(24);
    // Sintegra/IE — situação cadastral estadual muda raras vezes para
    // empresas ativas; 7d hard é o equilíbrio entre não martelar o SVRS
    // (que oscila em estabilidade) e refletir suspensões/baixas em janela
    // razoável para integrações fiscais.
    private static final Duration SINTEGRA_TTL = Duration.ofDays(7);
    // CND — certidões caducam em 180 dias (Federal/PGFN, FGTS) ou 6 meses
    // (TST). Mas o conteúdo do PDF é frozen no instante de emissão; só
    // mudaria se o cliente reemitisse. 6h hard absorve bursts de
    // habilitação em licitações sem servir um PDF com data desatualizada.
    private static final Duration CND_TTL = Duration.ofHours(6);
    // SIGTAP — tabela DataSUS é virada uma vez ao mês (competência). A
    // resposta processada no banco é imutável durante o ciclo; cabe um
    // TTL agressivo na borda Redis (30 dias) — o ETL noturno invalida via
    // CacheManager.evictAll quando promove uma nova competência.
    private static final Duration SIGTAP_TTL = Duration.ofDays(30);
    // Histórico veicular (premium OlhoNoCarro/Checkauto + free-tier scrapers).
    // O passado de um veículo — se já foi a leilão ou bateu — é um dado
    // imutável ou de baixíssima volatilidade: um registro de leilão/sinistro
    // não "desaparece". Hard-TTL 30d blinda as fontes premium (custo por
    // consulta) e os endpoints gratuitos Cloudflare-fronted contra hits
    // repetidos; soft-TTL 7d (definido no service) dispara refresh-ahead em
    // background sem nunca travar o caller no caminho crítico.
    private static final Duration HISTORICO_VEICULAR_TTL = Duration.ofDays(30);

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
    private static final String CAMBIO_HISTORICO_CACHE = "cambioHistorico";
    private static final String SANCOES_CACHE = "sancoes";
    private static final String PROCESSOS_CACHE = "processos";
    private static final String INDICADORES_APS_CACHE = "indicadoresAps";
    private static final String ISBNS_CACHE = "isbns";
    private static final String PIX_PARTICIPANTES_CACHE = "pixParticipantes";
    private static final String PTAX_CATALOG_CACHE = "ptaxCatalog";
    private static final String IBGE_UF_CACHE = "ibgeUf";
    private static final String IBGE_MUNICIPIOS_CACHE = "ibgeMunicipios";
    private static final String CVM_CORRETORAS_CACHE = "cvmCorretoras";
    private static final String CVM_FUNDOS_CACHE = "cvmFundos";
    private static final String B3_ACOES_CACHE = "b3Acoes";
    private static final String B3_FUNDOS_CACHE = "b3Fundos";
    private static final String DDD_CACHE = "ddd";
    private static final String CPTEC_CACHE = "cptec";
    private static final String REGISTRO_BR_CACHE = "registroBr";
    private static final String TUSS_CACHE = "tuss";
    private static final String LICITACOES_ATIVAS_CACHE = "licitacoesAtivas";
    private static final String LICITACOES_DETALHE_CACHE = "licitacoesDetalhe";
    private static final String SIMPLES_CACHE = "simples";
    private static final String SINTEGRA_CACHE = "sintegra";
    private static final String CND_CACHE = "cnd";
    public static final String SIGTAP_CACHE = "sigtap";
    private static final String HISTORICO_VEICULAR_CACHE = "historicoVeicular";

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
                Map.entry(CAMBIO_HISTORICO_CACHE, baseConfig().entryTtl(CAMBIO_HISTORICO_TTL)),
                Map.entry(SANCOES_CACHE, baseConfig().entryTtl(SANCOES_TTL)),
                Map.entry(PROCESSOS_CACHE, baseConfig().entryTtl(PROCESSOS_TTL)),
                Map.entry(INDICADORES_APS_CACHE, baseConfig().entryTtl(INDICADORES_APS_TTL)),
                Map.entry(ISBNS_CACHE, baseConfig().entryTtl(ISBNS_TTL)),
                Map.entry(PIX_PARTICIPANTES_CACHE, baseConfig().entryTtl(PIX_PARTICIPANTES_TTL)),
                Map.entry(PTAX_CATALOG_CACHE, baseConfig().entryTtl(PTAX_CATALOG_TTL)),
                Map.entry(IBGE_UF_CACHE, baseConfig().entryTtl(IBGE_TTL)),
                Map.entry(IBGE_MUNICIPIOS_CACHE, baseConfig().entryTtl(IBGE_TTL)),
                Map.entry(CVM_CORRETORAS_CACHE, baseConfig().entryTtl(CVM_TTL)),
                Map.entry(CVM_FUNDOS_CACHE, baseConfig().entryTtl(CVM_TTL)),
                Map.entry(B3_ACOES_CACHE, baseConfig().entryTtl(B3_TTL)),
                Map.entry(B3_FUNDOS_CACHE, baseConfig().entryTtl(B3_TTL)),
                Map.entry(DDD_CACHE, baseConfig().entryTtl(DDD_TTL)),
                Map.entry(CPTEC_CACHE, baseConfig().entryTtl(CPTEC_TTL)),
                Map.entry(REGISTRO_BR_CACHE, baseConfig().entryTtl(REGISTRO_BR_TTL)),
                Map.entry(TUSS_CACHE, baseConfig().entryTtl(TUSS_TTL)),
                Map.entry(LICITACOES_ATIVAS_CACHE, baseConfig().entryTtl(LICITACOES_ATIVAS_TTL)),
                Map.entry(LICITACOES_DETALHE_CACHE, baseConfig().entryTtl(LICITACOES_DETALHE_TTL)),
                Map.entry(SIMPLES_CACHE, baseConfig().entryTtl(SIMPLES_TTL)),
                Map.entry(SINTEGRA_CACHE, baseConfig().entryTtl(SINTEGRA_TTL)),
                Map.entry(CND_CACHE, baseConfig().entryTtl(CND_TTL)),
                Map.entry(SIGTAP_CACHE, baseConfig().entryTtl(SIGTAP_TTL)),
                Map.entry(HISTORICO_VEICULAR_CACHE, baseConfig().entryTtl(HISTORICO_VEICULAR_TTL))
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
