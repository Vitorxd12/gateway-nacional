package br.com.cernebr.gateway_nacional.operacional.status.service;

import br.com.cernebr.gateway_nacional.operacional.status.dto.StatusResponse;
import br.com.cernebr.gateway_nacional.operacional.status.dto.StatusResponse.DominioStatus;
import br.com.cernebr.gateway_nacional.operacional.status.dto.StatusResponse.GatewayHealth;
import br.com.cernebr.gateway_nacional.operacional.status.dto.StatusResponse.ProviderHealth;
import br.com.cernebr.gateway_nacional.operacional.status.dto.StatusResponse.RedisHealth;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agrega o snapshot de saúde exposto em {@code GET /api/v1/status}.
 *
 * <h2>Origem dos dados</h2>
 * <ol>
 *   <li><b>Métricas:</b> {@link MeterRegistry} já tem todos os contadores
 *       {@code gateway.provider.requests} (tags {@code domain},
 *       {@code provider}, {@code outcome}) e timers
 *       {@code gateway.provider.latency} alimentados pelos {@code HedgedExecutor}
 *       e services. Não inventamos uma instrumentação paralela — reusamos a
 *       já consumida pelo Prometheus em {@code /actuator/prometheus}.</li>
 *   <li><b>Circuit Breakers:</b> {@link CircuitBreakerRegistry} é injetado
 *       pelo Resilience4j Spring Boot Starter. Iteramos todos os CBs
 *       registrados e indexamos por nome normalizado.</li>
 *   <li><b>Redis:</b> {@link RedisConnectionFactory#getConnection() ping()}
 *       direto. Latência cronometrada inline; exceção vira "indisponivel"
 *       sem propagar — o Redis fora não derruba o status, só polui um campo.</li>
 * </ol>
 *
 * <h2>Match métrica → Circuit Breaker</h2>
 * <p>Os providers tagueiam {@code provider=<lower(providerName())>} nas
 * métricas (ex.: {@code "bcb-olinda-ptax"}), enquanto os CBs são nomeados
 * em camelCase + sufixo {@code "CB"} (ex.: {@code "cambioBcbOlindaCB"}). Não
 * há mapping declarativo entre os dois. Usamos um match fuzzy: normalizamos
 * ambos para letras minúsculas sem hífen/underscore e procuramos correspondência
 * por substring. Se o provider tag normalizado ocorre como substring no CB
 * normalizado, é match. Best-effort — provider sem CB casado fica com
 * {@code circuitBreaker = null} (o status do provider é então derivado
 * apenas das métricas).</p>
 *
 * <h2>Cálculo de status</h2>
 * <p>Por provider:</p>
 * <ul>
 *   <li>CB {@code OPEN} ou taxa de falha &gt; 50% → {@code indisponivel};</li>
 *   <li>CB {@code HALF_OPEN} ou taxa de falha entre 5%–50% → {@code degradado};</li>
 *   <li>CB {@code CLOSED} ou ausente + taxa &le; 5% → {@code operacional};</li>
 *   <li>Sem requisições no histórico → {@code sem_trafego}.</li>
 * </ul>
 *
 * <p>Por domínio: pior caso entre os providers ativos. Se todos os providers
 * estão {@code sem_trafego}, o domínio também aparece como {@code sem_trafego}
 * (cliente entende que aquele domínio ainda não foi exercido nesta JVM).</p>
 *
 * <h2>Sem cache de servidor</h2>
 * <p>Status deve ser real-time. Aceitamos o custo de ~50ms por chamada
 * (iteração linear sobre meters + ping Redis). Status pages bem comportados
 * batem 1×/min — carga insignificante.</p>
 */
@Slf4j
@Service
public class StatusService {

    /** Janela em que o provider é considerado degradado (5% a 50% de falhas). */
    private static final double DEGRADADO_MIN = 0.05;
    private static final double INDISPONIVEL_MIN = 0.50;

    private static final String STATUS_OPERACIONAL = "operacional";
    private static final String STATUS_DEGRADADO = "degradado";
    private static final String STATUS_INDISPONIVEL = "indisponivel";
    private static final String STATUS_SEM_TRAFEGO = "sem_trafego";
    private static final String STATUS_NAO_CONFIGURADO = "nao_configurado";

    private final MeterRegistry meterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RedisConnectionFactory redisConnectionFactory;
    private final Environment environment;
    private final String versao;

    public StatusService(MeterRegistry meterRegistry,
                         CircuitBreakerRegistry circuitBreakerRegistry,
                         @Nullable RedisConnectionFactory redisConnectionFactory,
                         Environment environment,
                         @Value("${gateway.version:0.0.1-SNAPSHOT}") String versao) {
        this.meterRegistry = meterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.redisConnectionFactory = redisConnectionFactory;
        this.environment = environment;
        this.versao = versao;
    }

    public StatusResponse snapshot() {
        List<DominioStatus> dominios = computeDominios();
        GatewayHealth gateway = computeGateway(dominios);
        return new StatusResponse(
                versao,
                ambiente(),
                Instant.now(),
                gateway,
                dominios
        );
    }

    // ─── DOMINIOS ─────────────────────────────────────────────────────────

    private List<DominioStatus> computeDominios() {
        // Acumula contagens (success/failure/not-found) por (domain, provider)
        // a partir do counter gateway.provider.requests.
        Map<String, Map<String, OutcomeTally>> tally = new LinkedHashMap<>();
        for (Meter meter : meterRegistry.find("gateway.provider.requests").meters()) {
            if (!(meter instanceof Counter counter)) continue;
            String domain = tag(counter, "domain");
            String provider = tag(counter, "provider");
            String outcome = tag(counter, "outcome");
            if (domain == null || provider == null) continue;

            tally.computeIfAbsent(domain, k -> new LinkedHashMap<>())
                    .computeIfAbsent(provider, k -> new OutcomeTally())
                    .add(outcome, counter.count());
        }

        // Latência média (acumulada / contagem) por (domain, provider).
        Map<String, Map<String, LatencyTally>> latency = new HashMap<>();
        for (Meter meter : meterRegistry.find("gateway.provider.latency").meters()) {
            if (!(meter instanceof Timer timer)) continue;
            String domain = tag(timer, "domain");
            String provider = tag(timer, "provider");
            if (domain == null || provider == null) continue;
            latency.computeIfAbsent(domain, k -> new HashMap<>())
                    .computeIfAbsent(provider, k -> new LatencyTally())
                    .merge(timer);
        }

        // Index CBs por nome normalizado para match fuzzy O(1).
        Map<String, CircuitBreaker> cbIndex = new HashMap<>();
        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            cbIndex.put(normalize(cb.getName()), cb);
        }

        List<DominioStatus> out = new ArrayList<>(tally.size());
        for (Map.Entry<String, Map<String, OutcomeTally>> domainEntry : tally.entrySet()) {
            String domain = domainEntry.getKey();
            List<ProviderHealth> providers = new ArrayList<>(domainEntry.getValue().size());
            long totalRequests = 0;
            long totalSuccess = 0;

            for (Map.Entry<String, OutcomeTally> providerEntry : domainEntry.getValue().entrySet()) {
                String provider = providerEntry.getKey();
                OutcomeTally t = providerEntry.getValue();
                LatencyTally lt = latency.getOrDefault(domain, Map.of()).get(provider);

                String cbState = findCircuitBreakerState(cbIndex, provider);
                ProviderHealth health = buildProviderHealth(provider, t, lt, cbState);
                providers.add(health);

                totalRequests += t.total();
                totalSuccess += t.success;
            }

            providers.sort(Comparator.comparing(ProviderHealth::nome));
            String domainStatus = worstStatus(providers);
            Double successRate = totalRequests == 0
                    ? null
                    : (double) totalSuccess / totalRequests;
            out.add(new DominioStatus(domain, domainStatus, totalRequests, successRate, providers));
        }
        out.sort(Comparator.comparing(DominioStatus::nome));
        return out;
    }

    private ProviderHealth buildProviderHealth(String provider, OutcomeTally t, @Nullable LatencyTally lt, @Nullable String cbState) {
        long total = t.total();
        Double successRate = total == 0 ? null : (double) t.success / total;
        Long meanMs = (lt == null || lt.count == 0)
                ? null
                : Math.round(lt.totalMs / lt.count);
        String status = providerStatus(cbState, successRate, total);
        return new ProviderHealth(provider, status, total, successRate, meanMs, cbState);
    }

    /**
     * Match fuzzy provider tag → CB name. Estratégia: ambos normalizados (só
     * letras minúsculas); o tag deve aparecer como substring dentro do nome
     * normalizado do CB. Greedy — primeiro match vence. Falha silenciosa.
     */
    @Nullable
    private String findCircuitBreakerState(Map<String, CircuitBreaker> cbIndex, String providerTag) {
        String needle = normalize(providerTag);
        if (needle.isEmpty()) return null;
        // Match direto primeiro
        CircuitBreaker direct = cbIndex.get(needle);
        if (direct != null) return direct.getState().name();
        CircuitBreaker direct2 = cbIndex.get(needle + "cb");
        if (direct2 != null) return direct2.getState().name();
        // Fallback: substring
        for (Map.Entry<String, CircuitBreaker> e : cbIndex.entrySet()) {
            if (e.getKey().contains(needle)) {
                return e.getValue().getState().name();
            }
        }
        return null;
    }

    private static String providerStatus(@Nullable String cbState, @Nullable Double successRate, long total) {
        if ("OPEN".equals(cbState) || "FORCED_OPEN".equals(cbState)) return STATUS_INDISPONIVEL;
        if (total == 0) return STATUS_SEM_TRAFEGO;
        double failureRate = successRate == null ? 0.0 : 1.0 - successRate;
        if (failureRate >= INDISPONIVEL_MIN) return STATUS_INDISPONIVEL;
        if ("HALF_OPEN".equals(cbState) || failureRate >= DEGRADADO_MIN) return STATUS_DEGRADADO;
        return STATUS_OPERACIONAL;
    }

    /**
     * Status do domínio é o pior caso entre os providers que tiveram tráfego.
     * Se todos os providers ainda não foram exercidos nesta JVM, o domínio
     * aparece como sem_trafego (não como operacional, para não dar falsa
     * sensação de saúde antes da primeira request).
     */
    private static String worstStatus(List<ProviderHealth> providers) {
        boolean hasIndisp = false, hasDegradado = false, hasOperacional = false;
        for (ProviderHealth p : providers) {
            switch (p.status()) {
                case STATUS_INDISPONIVEL -> hasIndisp = true;
                case STATUS_DEGRADADO -> hasDegradado = true;
                case STATUS_OPERACIONAL -> hasOperacional = true;
                default -> { /* sem_trafego ignorado para agregação */ }
            }
        }
        if (hasOperacional && (hasIndisp || hasDegradado)) return STATUS_DEGRADADO;
        if (hasIndisp) return STATUS_INDISPONIVEL;
        if (hasDegradado) return STATUS_DEGRADADO;
        if (hasOperacional) return STATUS_OPERACIONAL;
        return STATUS_SEM_TRAFEGO;
    }

    // ─── GATEWAY HEALTH ──────────────────────────────────────────────────

    private GatewayHealth computeGateway(List<DominioStatus> dominios) {
        RedisHealth redis = checkRedis();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        return new GatewayHealth(rollupStatus(redis, dominios), formatDuration(uptimeMs), redis);
    }

    private static String rollupStatus(RedisHealth redis, List<DominioStatus> dominios) {
        // Redis indisponível derruba o status agregado para degradado (gateway
        // segue respondendo, mas cache no chão = latência alta + tráfego upstream).
        boolean redisDown = "indisponivel".equals(redis.status());
        boolean anyDegradado = false, anyIndisp = false;
        for (DominioStatus d : dominios) {
            if (STATUS_INDISPONIVEL.equals(d.status())) anyIndisp = true;
            if (STATUS_DEGRADADO.equals(d.status())) anyDegradado = true;
        }
        if (anyIndisp) return STATUS_DEGRADADO; // gateway segue ok, parte do tráfego sofre
        if (anyDegradado || redisDown) return STATUS_DEGRADADO;
        return STATUS_OPERACIONAL;
    }

    private RedisHealth checkRedis() {
        if (redisConnectionFactory == null) {
            return new RedisHealth(STATUS_NAO_CONFIGURADO, -1);
        }
        long t0 = System.nanoTime();
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            String reply = conn.ping();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = "PONG".equalsIgnoreCase(reply);
            return new RedisHealth(ok ? STATUS_OPERACIONAL : STATUS_INDISPONIVEL, elapsedMs);
        } catch (Exception ex) {
            log.warn("Redis ping falhou no status check: {}", ex.toString());
            return new RedisHealth(STATUS_INDISPONIVEL, -1);
        }
    }

    private String ambiente() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", profiles);
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────

    @Nullable
    private static String tag(Meter meter, String key) {
        String v = meter.getId().getTag(key);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String formatDuration(long ms) {
        Duration d = Duration.ofMillis(ms);
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (days > 0 || hours > 0) sb.append(hours).append("h ");
        if (days > 0 || hours > 0 || minutes > 0) sb.append(minutes).append("m");
        if (sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    /** Acumulador de contagens (success/failure/not-found) por provider. */
    private static final class OutcomeTally {
        double success = 0;
        double other = 0;

        void add(@Nullable String outcome, double count) {
            if ("success".equals(outcome)) success += count;
            else other += count;
        }

        long total() {
            return (long) Math.round(success + other);
        }
    }

    /** Acumulador de latência (soma + contagem) — média = total/count. */
    private static final class LatencyTally {
        double totalMs = 0;
        long count = 0;

        void merge(Timer t) {
            totalMs += t.totalTime(TimeUnit.MILLISECONDS);
            count += t.count();
        }
    }
}
