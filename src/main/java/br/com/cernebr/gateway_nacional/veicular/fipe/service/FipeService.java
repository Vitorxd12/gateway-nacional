package br.com.cernebr.gateway_nacional.veicular.fipe.service;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.veicular.fipe.client.BrasilApiFipeClient;
import br.com.cernebr.gateway_nacional.veicular.fipe.client.FipeClientProvider;
import br.com.cernebr.gateway_nacional.veicular.fipe.client.FipeNavegacaoProvider;
import br.com.cernebr.gateway_nacional.veicular.fipe.client.FipeOrgScraperClient;
import br.com.cernebr.gateway_nacional.veicular.fipe.client.ParallelumFipeClient;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeMarcaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeMarcasEnvelope;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTabelaReferenciaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTabelasEnvelope;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTipoVeiculo;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeVeiculoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeVeiculosEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Orchestrates the cascade fallback for FIPE vehicle quotes.
 *
 * <p><b>ATENÇÃO: Não migrar para
 * {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor} nem
 * {@link br.com.cernebr.gateway_nacional.config.RefreshAheadCache}.</b>
 * O provider primário é um scraper {@code FlareSolverr} + Chromium contra
 * veiculos.fipe.org.br. A cascata sequencial atua como proteção de recursos:
 * sob hedge, cada request dispararia uma sessão Chromium ainda que os
 * fallbacks REST estivessem disponíveis. RAC dispararia o mesmo trabalho
 * pesado em background. Mantenha {@code @Cacheable} puro com TTL longo.</p>
 *
 * <p>Order: <b>FIPE-Oficial (FlareSolverr) → BrasilAPI → Parallelum</b>.
 * The official scraper became the primary on 2026-05-08 — at that date both
 * BrasilAPI's and Parallelum's FIPE proxies were broken upstream
 * (BrasilAPI returns 500/AxiosError-403 for every FIPE route, Parallelum's
 * direct-by-fipe-code v1 path was retired). Scraping the foundation
 * directly via the FlareSolverr sidecar bypasses both intermediaries and
 * recovers full FIPE availability without external dependencies. The two
 * legacy providers stay in the cascade as deferred fallbacks so the gateway
 * keeps working the day they recover.</p>
 *
 * <p>Cache key is composite — {@code "{codigoFipe}-{anoModelo}"} — so quotes
 * for different years of the same model never collide. TTL of 15 days is
 * a safe midpoint: FIPE publishes monthly updates, and a 15-day window
 * guarantees that customers see the new month-of-reference before the next
 * publication cycle starts.</p>
 */
@Slf4j
@Service
public class FipeService {

    private static final String DOMAIN = "fipe";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<FipeClientProvider> providersInOrder;
    /**
     * Cascata de navegação separada da cotação: Parallelum não expõe
     * marcas/veiculos/tabelas na free tier, então só scraper + BrasilAPI.
     * Ordem mantida intencional pra que indisponibilidade do scraper caia
     * ruidosamente na BrasilAPI (que historicamente é bloqueada pelo upstream
     * FIPE) — métricas vão revelar a saúde dos dois.
     */
    private final List<FipeNavegacaoProvider> navProvidersInOrder;
    private final MeterRegistry meterRegistry;

    public FipeService(FipeOrgScraperClient primary,
                       BrasilApiFipeClient legacyOne,
                       ParallelumFipeClient legacyTwo,
                       MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, legacyOne, legacyTwo);
        this.navProvidersInOrder = List.of(primary, legacyOne);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "fipe", key = "#codigoFipe + '-' + #anoModelo")
    public FipePrecoResponse findPreco(String codigoFipe, String anoModelo) {
        for (FipeClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                FipePrecoResponse response = provider.fetchPreco(codigoFipe, anoModelo);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("FIPE {}-{} resolved by provider={}",
                        codigoFipe, anoModelo, provider.providerName());
                return response;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for FIPE {}-{} ({}). Cascading to next provider.",
                        provider.providerName(), codigoFipe, anoModelo, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de FIPE falharam após o fallback em cascata.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navegação FIPE — marcas, veiculos por marca, tabelas-de-referência.
    // Cascata sequencial entre [scraper, brasilApi]; mesmo @Cacheable("fipe")
    // do preco; chave inclui operação + parâmetros pra evitar colisão.
    // List<> é embrulhado em envelope (constraint do Spring Data Redis com
    // default-typing — ver FipeMarcasEnvelope javadoc).
    // ─────────────────────────────────────────────────────────────────────────

    @Cacheable(cacheNames = "fipe",
            key = "'marcas-' + #tipo + '-' + (#tabelaReferencia ?: 'latest')")
    public List<FipeMarcaResponse> listMarcas(FipeTipoVeiculo tipo, @Nullable Integer tabelaReferencia) {
        FipeMarcasEnvelope envelope = cascadeNav("marcas",
                p -> new FipeMarcasEnvelope(p.listMarcas(tipo, tabelaReferencia)),
                "tipo=" + tipo + " tabela=" + tabelaReferencia);
        return envelope.marcas();
    }

    @Cacheable(cacheNames = "fipe",
            key = "'veiculos-' + #tipo + '-' + #codigoMarca + '-' + (#tabelaReferencia ?: 'latest')")
    public List<FipeVeiculoResponse> listVeiculosByMarca(FipeTipoVeiculo tipo,
                                                        String codigoMarca,
                                                        @Nullable Integer tabelaReferencia) {
        FipeVeiculosEnvelope envelope = cascadeNav("veiculos",
                p -> new FipeVeiculosEnvelope(p.listVeiculosByMarca(tipo, codigoMarca, tabelaReferencia)),
                "tipo=" + tipo + " marca=" + codigoMarca + " tabela=" + tabelaReferencia);
        return envelope.veiculos();
    }

    @Cacheable(cacheNames = "fipe", key = "'tabelas'")
    public List<FipeTabelaReferenciaResponse> listTabelasReferencia() {
        FipeTabelasEnvelope envelope = cascadeNav("tabelas",
                p -> new FipeTabelasEnvelope(p.listTabelasReferencia()),
                "");
        return envelope.tabelas();
    }

    /**
     * Cascata genérica de navegação. Replica o loop do {@link #findPreco} mas
     * sobre {@link FipeNavegacaoProvider} — evita duplicar o boilerplate de
     * try/catch + métricas pra cada uma das 3 operações.
     */
    private <T> T cascadeNav(String operation,
                             java.util.function.Function<FipeNavegacaoProvider, T> call,
                             String contextForLog) {
        for (FipeNavegacaoProvider provider : navProvidersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                T result = call.apply(provider);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("FIPE-nav {} resolvido por provider={} ({})",
                        operation, provider.providerName(), contextForLog);
                return result;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} falhou em FIPE-nav {} ({}): {}. Cascateando.",
                        provider.providerName(), operation, contextForLog, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de navegação FIPE falharam para " + operation + " (" + contextForLog + ").");
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
