package br.com.cernebr.gateway_nacional.cadastral.cnpj.service;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.ArchiveCnpjPwClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjAClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjConsolidadoClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.CnpjWsClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.OpenCnpjClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.ReceitaWsConsolidadoClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Motor de consolidação do CNPJ canônico.
 *
 * <p><b>Estratégia:</b> dispara os 5 providers em paralelo via virtual threads
 * (Loom). Cada parcial é coletado independentemente; um provider lento ou que
 * estoure o limite duro de 10s não derruba o consolidado — entra na lista de
 * fontes sobreviventes apenas quem entregou payload válido até o deadline.</p>
 *
 * <p><b>Cache:</b> camada {@link RefreshAheadCache} aplica soft-TTL de 24h
 * (refresh-ahead em background) sobre o hard-TTL de 30d configurado em
 * {@code cnpjsConsolidados}. Dados cadastrais de PJ têm volatilidade
 * baixíssima (mudança de razão social/situação acontece em janela mensal);
 * o RAC garante que toda alteração propaga em ≤ 24h sem martelar os
 * providers no caminho crítico.</p>
 *
 * <p><b>Por que merge e não hedge:</b> hedge devolve o primeiro a responder e
 * descarta os demais — perderia a qualificação completa do sócio que só a
 * ReceitaWS entrega, ou a tabela de períodos Simples que só o CNPJá tem.
 * Aqui o consumidor recebe o <em>union</em> dos dados, e o cliente final
 * inspeciona {@code fontesSobreviventes} para auditar a procedência.</p>
 *
 * <p><b>Fail-soft:</b> se ≥1 provider sobreviver, devolve consolidado parcial
 * (mesmo que CnpjWs estivesse caído). Apenas quando os 5 falham é lançado
 * {@link ResourceUnavailableException} mapeado para HTTP 503.</p>
 */
@Slf4j
@Service
public class CnpjConsolidadoService {

    private static final String DOMAIN = "cnpjConsolidado";
    static final String CACHE_NAME = "cnpjsConsolidados";
    private static final Duration SOFT_TTL = Duration.ofHours(24);
    private static final Duration HARD_TIMEOUT_PER_PROVIDER = Duration.ofSeconds(10);

    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";
    private static final String METRIC_SURVIVORS = "gateway.cnpj.survivors";

    private final List<CnpjConsolidadoClientProvider> providers;
    private final RefreshAheadCache refreshAheadCache;
    private final MeterRegistry meterRegistry;

    private final ExecutorService parallelExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Ordem de prioridade do merge — fixada no construtor para garantir que o
     * comportamento de "primeiro não-nulo vence por campo" seja determinístico
     * em todas as JVMs.
     */
    public CnpjConsolidadoService(CnpjWsClient cnpjWs,
                                  OpenCnpjClient openCnpj,
                                  CnpjAClient cnpjA,
                                  ReceitaWsConsolidadoClient receitaWs,
                                  ArchiveCnpjPwClient archive,
                                  RefreshAheadCache refreshAheadCache,
                                  MeterRegistry meterRegistry) {
        this.providers = List.of(cnpjWs, openCnpj, cnpjA, receitaWs, archive);
        this.refreshAheadCache = refreshAheadCache;
        this.meterRegistry = meterRegistry;
    }

    @PreDestroy
    void shutdown() {
        parallelExecutor.shutdown();
    }

    public CnpjConsolidadoDTO findByCnpj(String cnpjLimpo) {
        return refreshAheadCache.get(CACHE_NAME, cacheKey(cnpjLimpo), SOFT_TTL,
                () -> consolidate(cnpjLimpo));
    }

    private CnpjConsolidadoDTO consolidate(String cnpj) {
        List<CompletableFuture<ProviderOutcome>> futures = providers.stream()
                .map(p -> CompletableFuture.supplyAsync(
                                () -> invokeAndMeasure(cnpj, p), parallelExecutor)
                        .orTimeout(HARD_TIMEOUT_PER_PROVIDER.toMillis(), TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> ProviderOutcome.failed(p.providerName(), ex)))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<CnpjConsolidadoDTO> parciaisOrdenados = new ArrayList<>();
        List<String> fontesSobreviventes = new ArrayList<>();
        for (CompletableFuture<ProviderOutcome> f : futures) {
            ProviderOutcome out = f.join();
            if (out.payload != null) {
                parciaisOrdenados.add(out.payload);
                fontesSobreviventes.add(out.providerName);
            }
        }

        meterRegistry.counter(METRIC_SURVIVORS,
                "domain", DOMAIN,
                "count", String.valueOf(fontesSobreviventes.size())).increment();

        if (parciaisOrdenados.isEmpty()) {
            throw new ResourceUnavailableException(DOMAIN,
                    "Todos os providers de CNPJ falharam ou estouraram o timeout — sem dados para consolidar.");
        }

        log.info("[cnpjConsolidado] cnpj={} sobreviventes={}", cnpj, fontesSobreviventes);
        return CnpjConsolidadoMerger.merge(cnpj, parciaisOrdenados, fontesSobreviventes);
    }

    private ProviderOutcome invokeAndMeasure(String cnpj, CnpjConsolidadoClientProvider provider) {
        String providerTag = provider.providerName().toLowerCase(Locale.ROOT);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CnpjConsolidadoDTO payload = provider.fetch(cnpj);
            recordOutcome(providerTag, "success", sample);
            return ProviderOutcome.ok(provider.providerName(), payload);
        } catch (RuntimeException ex) {
            recordOutcome(providerTag, "failure", sample);
            log.debug("[cnpjConsolidado] provider={} falhou cnpj={} causa={}",
                    provider.providerName(), cnpj, ex.getMessage());
            return ProviderOutcome.failed(provider.providerName(), ex);
        }
    }

    private void recordOutcome(String providerTag, String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", providerTag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", providerTag,
                "outcome", outcome).increment();
    }

    private static String cacheKey(String cnpj) {
        return "cnpj::" + cnpj;
    }

    private record ProviderOutcome(String providerName, CnpjConsolidadoDTO payload, Throwable error) {

        static ProviderOutcome ok(String name, CnpjConsolidadoDTO payload) {
            if (payload == null) {
                return new ProviderOutcome(name, null,
                        new IllegalStateException("Payload nulo do provider " + name));
            }
            return new ProviderOutcome(name, payload, null);
        }

        static ProviderOutcome failed(String name, Throwable ex) {
            if (ex instanceof TimeoutException) {
                return new ProviderOutcome(name, null, ex);
            }
            return new ProviderOutcome(name, null, ex);
        }
    }
}
