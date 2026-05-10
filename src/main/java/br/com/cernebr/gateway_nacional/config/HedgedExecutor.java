package br.com.cernebr.gateway_nacional.config;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Dispatcher de "hedged requests": dispara N providers em paralelo e retorna
 * o primeiro a responder com sucesso, cancelando os demais.
 *
 * <p><b>Por que hedge:</b> a cascata sequencial soma latências dos providers
 * que falham antes do primeiro sucesso (ex.: ViaCEP timeout de 5s + BrasilAPI
 * 800ms = 5.8s no caminho lento). Sob hedge, p99 vira aproximadamente
 * {@code max(latência mínima entre os provedores que respondem)} — para um
 * trio saudável tipicamente 500–800ms, mesmo se um deles está degradado.</p>
 *
 * <p><b>Por que virtual threads cabem aqui:</b> o projeto já roda Loom
 * ({@code spring.threads.virtual.enabled=true}) e o {@code RestClient}
 * outbound usa virtual-thread executor. Disparar 3 providers para cada
 * requisição entrante a 1k RPS = ~3k virtual threads vivas no pico —
 * memória residual de ordem de KB por thread, sem pressão sobre o pool de
 * platform threads. Não há justificativa para um {@code BlockingQueue} ou
 * pool bounded.</p>
 *
 * <p><b>Cancelamento dos perdedores:</b> {@link Future#cancel(boolean) cancel(true)}
 * interrompe a thread; o JDK {@link java.net.http.HttpClient} aborta a request
 * em curso. Há um trade-off conhecido: o Resilience4j Circuit Breaker pode
 * contar a interrupção como falha, poluindo a janela deslizante. A escolha é
 * deliberada — preservar quota dos providers (limite mais escasso) vale mais
 * que alguns pontos de ruído na taxa de falha do CB. Reavaliar se algum CB
 * começar a abrir prematuramente.</p>
 *
 * <p><b>Métricas emitidas (consolidadas aqui — services não duplicam mais):</b></p>
 * <ul>
 *   <li>{@code gateway.provider.requests} — counter, tags
 *       {@code domain}, {@code provider}, {@code outcome}. Conta cada
 *       <em>tentativa</em> que efetivamente rodou (perdedor cancelado antes
 *       de iniciar não conta). Sob hedge, este contador cresce ~N× por
 *       requisição entrante — dashboards devem usar
 *       {@code gateway.hedge.winner} para o número de chamadas reais ao
 *       gateway.</li>
 *   <li>{@code gateway.provider.latency} — timer, tags {@code domain},
 *       {@code provider}. Latência por tentativa, sucesso ou falha.</li>
 *   <li>{@code gateway.hedge.winner} — counter, tags {@code domain},
 *       {@code provider}. 1 incremento por chamada bem-sucedida ao
 *       {@link #anyOf}. Use para taxa de uso por provider e para a
 *       contagem real do domínio.</li>
 * </ul>
 */
@Slf4j
@Component
public class HedgedExecutor {

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";
    static final String METRIC_HEDGE_WINNER = "gateway.hedge.winner";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILURE = "failure";

    private final MeterRegistry meterRegistry;
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public HedgedExecutor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * Dispara todos os providers em paralelo e retorna o primeiro resultado
     * bem-sucedido. Cancela os demais. Lança {@link ResourceUnavailableException}
     * com o {@code domain} como providerName quando todos falham — preserva o
     * mesmo formato de erro que o cascade emitia, mantendo o contrato externo.
     *
     * @param domain rótulo do domínio (cep, cnpj, taxas…) — vira tag das
     *               métricas e providerName do erro agregado
     * @param providers list de tentativas paralelas. A ordem não tem significado
     *                  funcional sob hedge; manter ordem estável só ajuda revisão
     */
    public <T> T anyOf(String domain, List<NamedSupplier<T>> providers) {
        if (providers.isEmpty()) {
            throw new ResourceUnavailableException(domain,
                    "Nenhum provider configurado para o domínio: " + domain);
        }

        CompletionService<NamedResult<T>> cs = new ExecutorCompletionService<>(executor);
        List<Future<NamedResult<T>>> futures = providers.stream()
                .map(p -> cs.submit(() -> invokeAndMeasure(domain, p)))
                .toList();

        Throwable lastError = null;
        try {
            int remaining = futures.size();
            while (remaining > 0) {
                Future<NamedResult<T>> done = cs.take();
                remaining--;
                try {
                    NamedResult<T> result = done.get();
                    cancelRemaining(futures);
                    meterRegistry.counter(METRIC_HEDGE_WINNER,
                            "domain", domain,
                            "provider", result.providerTag()).increment();
                    log.info("[{}] Hedged winner: {}", domain, result.providerName());
                    return result.value();
                } catch (ExecutionException ex) {
                    lastError = ex.getCause();
                } catch (CancellationException ignored) {
                    // Future cancelado antes de ser observado — esperado quando
                    // o vencedor já retornou; não polui o lastError.
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cancelRemaining(futures);
            throw new ResourceUnavailableException(domain,
                    "Execução paralela de providers interrompida.", ie);
        }

        throw new ResourceUnavailableException(domain,
                "Todos os provedores de " + domain + " falharam após hedge paralelo.",
                lastError);
    }

    private <T> NamedResult<T> invokeAndMeasure(String domain, NamedSupplier<T> p) {
        String providerTag = p.name().toLowerCase(Locale.ROOT);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T value = p.supplier().get();
            recordOutcome(domain, providerTag, OUTCOME_SUCCESS, sample);
            return new NamedResult<>(p.name(), providerTag, value);
        } catch (Exception ex) {
            recordOutcome(domain, providerTag, OUTCOME_FAILURE, sample);
            // Repassa para CompletionService, onde o orquestrador trata como
            // falha de tentativa (não interrompe o restante do hedge).
            throw ex;
        }
    }

    private void recordOutcome(String domain, String providerTag, String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", domain)
                .tag("provider", providerTag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", domain,
                "provider", providerTag,
                "outcome", outcome).increment();
    }

    private static void cancelRemaining(List<? extends Future<?>> futures) {
        for (Future<?> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
    }

    /**
     * Tentativa nomeada — o nome alimenta logs e a tag {@code provider}.
     * Convenção: usar o {@code providerName()} já exposto pelos clients.
     */
    public record NamedSupplier<T>(String name, Supplier<T> supplier) {}

    private record NamedResult<T>(String providerName, String providerTag, T value) {}
}
