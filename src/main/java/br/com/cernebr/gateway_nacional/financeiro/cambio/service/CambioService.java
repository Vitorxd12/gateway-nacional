package br.com.cernebr.gateway_nacional.financeiro.cambio.service;

import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cambio.client.BcbOlindaCambioClient;
import br.com.cernebr.gateway_nacional.financeiro.cambio.client.BrasilApiCambioClient;
import br.com.cernebr.gateway_nacional.financeiro.cambio.client.CambioClient;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioEnvelope;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Orquestra a consulta de cotações em duas camadas:
 *
 * <ol>
 *   <li><b>Tier 1 — PTAX oficial (hedge):</b> {@link HedgedExecutor} dispara
 *       {@link BrasilApiCambioClient} e {@link BcbOlindaCambioClient} em
 *       paralelo. O primeiro a retornar a PTAX vence.</li>
 *   <li><b>Tier 2 — AwesomeAPI (cascata):</b> se o tier PTAX exaurir (ambos
 *       os providers falharam OU algum par é não-PTAX-elegível, ex.: cripto,
 *       cross-currency sem BRL), cai para {@link CambioClient} (AwesomeAPI),
 *       que cobre qualquer par e mantém a alta disponibilidade.</li>
 * </ol>
 *
 * <h2>Por que duas camadas e não hedge único entre os 3</h2>
 * <p>PTAX (BCB) e AwesomeAPI respondem perguntas semanticamente diferentes —
 * PTAX é fixing oficial diário (regulatório, fiscal, contábil); AwesomeAPI é
 * spot real-time comercial. Disparar AwesomeAPI <em>sempre</em> em paralelo
 * com PTAX queimaria a quota do AwesomeAPI mesmo quando o PTAX já respondeu
 * (a maioria dos casos). A cascata garante que o AwesomeAPI só é invocado
 * quando o PTAX realmente não pode atender.</p>
 *
 * <p><b>Priorização "PTAX primeiro" é estrutural</b>, não circunstancial:
 * o tier 1 sempre é tentado antes do tier 2, então clientes recebem dados
 * oficiais sempre que possível. O mapeamento PTAX → {@link CambioResponse}
 * preserva o contrato (RULE A); a única diferença observável é que
 * {@code variacao} fica {@code null} quando vem do PTAX (o BCB não publica
 * variação intra-dia — é fixing).</p>
 *
 * <h2>Cache e chave normalizada</h2>
 * <p>{@code @Cacheable} mantém o comportamento atual — chave normalizada
 * (UPPERCASE + ordem alfabética estável) colapsa rajadas de dashboards/ERPs
 * em uma única chamada upstream a cada 3 minutos por combinação de pares,
 * independentemente de qual tier respondeu.</p>
 */
@Slf4j
@Service
public class CambioService {

    private static final String DOMAIN = "cambio";
    private static final String PTAX_TIER = "cambio-ptax";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final BrasilApiCambioClient brasilApiPtax;
    private final BcbOlindaCambioClient bcbOlindaPtax;
    private final CambioClient awesomeApi;
    private final HedgedExecutor hedgedExecutor;
    private final MeterRegistry meterRegistry;

    public CambioService(BrasilApiCambioClient brasilApiPtax,
                         BcbOlindaCambioClient bcbOlindaPtax,
                         CambioClient awesomeApi,
                         HedgedExecutor hedgedExecutor,
                         MeterRegistry meterRegistry) {
        this.brasilApiPtax = brasilApiPtax;
        this.bcbOlindaPtax = bcbOlindaPtax;
        this.awesomeApi = awesomeApi;
        this.hedgedExecutor = hedgedExecutor;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "cambio", key = "T(br.com.cernebr.gateway_nacional.financeiro.cambio.service.CambioService).normalizar(#pares)")
    public CambioEnvelope consultar(String pares) {
        // Tier 1: PTAX hedge.
        try {
            List<CambioResponse> ptax = hedgedExecutor.anyOf(PTAX_TIER, List.of(
                    new NamedSupplier<>(brasilApiPtax.providerName(), () -> brasilApiPtax.fetchPtax(pares)),
                    new NamedSupplier<>(bcbOlindaPtax.providerName(), () -> bcbOlindaPtax.fetchPtax(pares))
            ));
            log.info("Câmbio PTAX resolvido pares={} resultados={}", pares, ptax.size());
            return new CambioEnvelope(ptax);
        } catch (ResourceUnavailableException ptaxFailure) {
            // Tier 2: AwesomeAPI fallback. Loga em INFO porque cascata para
            // AwesomeAPI é caminho legítimo (pares cripto, cross-currency,
            // ou ambos PTAX providers down).
            log.info("PTAX tier exausted for pares={} ({}). Cascading to AwesomeAPI fallback.",
                    pares, ptaxFailure.getMessage());
            return consultarAwesomeFallback(pares, ptaxFailure);
        }
    }

    private CambioEnvelope consultarAwesomeFallback(String pares, Throwable ptaxFailure) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CambioEnvelope envelope = new CambioEnvelope(awesomeApi.fetch(pares));
            recordOutcome(awesomeApi.providerName(), "success", sample);
            log.info("Câmbio AwesomeAPI fallback resolvido pares={} resultados={}",
                    pares, envelope.cotacoes().size());
            return envelope;
        } catch (RuntimeException ex) {
            recordOutcome(awesomeApi.providerName(), "failure", sample);
            log.warn("AwesomeAPI fallback falhou para câmbio pares={} ({}).", pares, ex.getMessage());
            // Exaustão total das duas camadas — propagamos como
            // ResourceUnavailableException para o GlobalExceptionHandler
            // emitir 503 ProblemDetail. Preservamos o ptaxFailure original
            // como suppressed para diagnóstico em logs.
            ResourceUnavailableException unified = new ResourceUnavailableException("cambio",
                    "Tanto a PTAX (BrasilAPI/BCB OLINDA) quanto o AwesomeAPI falharam.", ex);
            unified.addSuppressed(ptaxFailure);
            throw unified;
        }
    }

    /**
     * Normaliza o argumento de pares para uma chave de cache estável:
     * uppercase, sem espaços, ordenado alfabeticamente. Pública (e estática)
     * porque o SpEL do {@link Cacheable} resolve a chave por reflexão.
     */
    public static String normalizar(String pares) {
        if (pares == null || pares.isBlank()) {
            return "";
        }
        return Arrays.stream(pares.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private void recordOutcome(String providerName, String outcome, Timer.Sample sample) {
        String providerTag = providerName.toLowerCase(Locale.ROOT);
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", providerTag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", providerTag,
                "outcome", outcome).increment();
    }
}
