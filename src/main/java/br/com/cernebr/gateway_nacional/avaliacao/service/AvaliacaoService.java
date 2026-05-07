package br.com.cernebr.gateway_nacional.avaliacao.service;

import br.com.cernebr.gateway_nacional.avaliacao.client.MercadoClientProvider;
import br.com.cernebr.gateway_nacional.avaliacao.dto.AvaliacaoResponse;
import br.com.cernebr.gateway_nacional.avaliacao.dto.DadosMercado;
import br.com.cernebr.gateway_nacional.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.fipe.service.FipeService;
import br.com.cernebr.gateway_nacional.placa.dto.PlacaResponse;
import br.com.cernebr.gateway_nacional.placa.service.PlacaService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the cross-domain valuation: Placa identification + FIPE
 * reference + real-market scraping. The FIPE call and the marketplace
 * fan-out run concurrently on a single virtual-thread executor, so total
 * wall-time tracks the slowest single provider rather than the sum.
 *
 * <p><b>FIPE auto-discovery</b> — when the placa cascade resolves through
 * the {@code PlacaFipeScraperClient}, the {@link PlacaResponse} carries the
 * {@code codigoFipe} associated with that placa. This service detects the
 * field, plugs it straight into the FIPE lookup, and the response shows up
 * with the comparative reference in place — the caller never needed to
 * supply the code by hand. Token-free deploy, zero-friction Avaliação.</p>
 *
 * <p>Failure semantics are layered:
 * <ul>
 *   <li>Placa is mandatory — its failure aborts the whole call (the cascade
 *       inside {@link PlacaService} already handles upstream degradation);</li>
 *   <li>FIPE is best-effort — when no code is supplied <i>and</i> the placa
 *       provider did not publish one, the comparison block is skipped and
 *       the score reflects that;</li>
 *   <li>Each scraper is independent — one failure does not poison the others
 *       thanks to {@link CompletableFuture#exceptionally(java.util.function.Function)}.</li>
 * </ul>
 */
@Slf4j
@Service
public class AvaliacaoService {

    private static final String DOMAIN = "avaliacao";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    /** Tolerance band around FIPE — ±5% counts as "in line". */
    private static final BigDecimal LOWER_BOUND = new BigDecimal("0.95");
    private static final BigDecimal UPPER_BOUND = new BigDecimal("1.05");

    private static final String SCORE_ABOVE = "Acima da FIPE";
    private static final String SCORE_BELOW = "Abaixo da FIPE";
    private static final String SCORE_IN_LINE = "Em linha com a FIPE";
    private static final String SCORE_NO_FIPE = "FIPE não fornecida (informe codigoFipe)";
    private static final String SCORE_NO_MARKET = "Sem dados de mercado disponíveis";
    private static final String SCORE_UNAVAILABLE = "Avaliação indisponível";

    private final PlacaService placaService;
    private final FipeService fipeService;
    private final List<MercadoClientProvider> scrapers;
    private final MeterRegistry meterRegistry;

    public AvaliacaoService(PlacaService placaService,
                            FipeService fipeService,
                            List<MercadoClientProvider> scrapers,
                            MeterRegistry meterRegistry) {
        this.placaService = placaService;
        this.fipeService = fipeService;
        this.scrapers = List.copyOf(scrapers);
        this.meterRegistry = meterRegistry;
    }

    /**
     * Full pipeline — identifies the vehicle by placa, then composes FIPE
     * reference and market snapshot. Fails fast (HTTP 503) when the placa
     * cascade exhausts all providers.
     *
     * <p><b>FIPE auto-discovery:</b> the placa cascade may itself surface
     * a {@code codigoFipe} (today only the {@code PlacaFipeScraperClient}
     * publishes it; WDApi/Keplaca do not). When that happens, the gateway
     * stitches the bridge automatically — FIPE is queried with the
     * discovered code and the caller does not need to provide one.</p>
     *
     * <p><b>Precedence:</b> a caller-supplied {@code codigoFipe} always
     * wins (they may know a specific year-variant code better than the
     * scraper). The placa-discovered code only fills the gap when the
     * caller stayed silent. Result: token-free deploys still get full
     * Avaliação response, while sophisticated callers retain control.</p>
     */
    public AvaliacaoResponse avaliarPorPlaca(String placa, String codigoFipe) {
        PlacaResponse dadosVeiculo = placaService.findByPlaca(placa);
        int ano = dadosVeiculo.anoModelo() > 0 ? dadosVeiculo.anoModelo() : dadosVeiculo.anoFabricacao();
        String effectiveFipe = pickEffectiveCodigoFipe(codigoFipe, dadosVeiculo.codigoFipe());
        return composeAvaliacao(
                dadosVeiculo.placa(),
                dadosVeiculo,
                dadosVeiculo.marca(),
                dadosVeiculo.modelo(),
                ano,
                effectiveFipe
        );
    }

    /**
     * Decides which {@code codigoFipe} to send to the FIPE service. Caller-
     * supplied code wins when present; otherwise the placa-discovered code
     * fills in. {@code null} when neither source provides one — the score
     * resolver downstream surfaces that to the user.
     */
    private static String pickEffectiveCodigoFipe(String fromCaller, String fromPlaca) {
        if (fromCaller != null && !fromCaller.isBlank()) return fromCaller;
        if (fromPlaca != null && !fromPlaca.isBlank()) return fromPlaca;
        return null;
    }

    /**
     * Token-free pipeline — bypasses {@link PlacaService} entirely. Useful
     * when placa providers (WDApi/Keplaca) are blocked, out of credits, or
     * the caller already knows the vehicle. {@code placa} and
     * {@code dadosVeiculo} on the response arrive {@code null} by design.
     */
    public AvaliacaoResponse avaliarPorVeiculo(String marca, String modelo, int ano, String codigoFipe) {
        return composeAvaliacao(null, null, marca, modelo, ano, codigoFipe);
    }

    /**
     * Shared composition path used by both entry points. Runs the FIPE
     * lookup and the marketplace scraping <b>concurrently</b> on a single
     * virtual-thread executor — wall-time tracks {@code max(fipe, slowest scraper)},
     * not the sum. Both legs are individually exception-safe (FIPE returns
     * {@code null} on failure, scrapers degrade to empty list), so
     * {@code .join()} on either future cannot throw.
     */
    private AvaliacaoResponse composeAvaliacao(String placa,
                                               PlacaResponse dadosVeiculo,
                                               String marca,
                                               String modelo,
                                               int ano,
                                               String codigoFipe) {
        FipePrecoResponse referenciaFipe;
        DadosMercado mercado;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<FipePrecoResponse> fipeFuture =
                    CompletableFuture.supplyAsync(() -> fetchFipeIfPossible(codigoFipe, ano), executor);
            CompletableFuture<DadosMercado> mercadoFuture =
                    CompletableFuture.supplyAsync(() -> scrapeMercadoOn(marca, modelo, ano, executor), executor);
            referenciaFipe = fipeFuture.join();
            mercado = mercadoFuture.join();
        }
        String score = computeScore(referenciaFipe, mercado);
        return new AvaliacaoResponse(placa, dadosVeiculo, referenciaFipe, mercado, score);
    }

    private FipePrecoResponse fetchFipeIfPossible(String codigoFipe, int anoModelo) {
        if (codigoFipe == null || codigoFipe.isBlank()) {
            return null;
        }
        try {
            return fipeService.findPreco(codigoFipe, String.valueOf(anoModelo));
        } catch (Exception ex) {
            log.warn("FIPE lookup failed for codigo={} ano={} ({}). Returning null reference.",
                    codigoFipe, anoModelo, ex.getMessage());
            return null;
        }
    }

    /**
     * Fans out to every scraper in parallel on the executor passed in by
     * {@link #composeAvaliacao} — no nested executor allocation, so the
     * outer FIPE/Mercado split and the inner scraper fan-out share the
     * same virtual-thread pool. Failures inside {@link #safeFetch} are
     * absorbed (empty list returned), so a single broken marketplace
     * cannot collapse the join.
     */
    private DadosMercado scrapeMercadoOn(String marca, String modelo, int ano, ExecutorService executor) {
        List<String> linksReferencia = scrapers.stream()
                .map(s -> s.buildSearchUrl(marca, modelo, ano))
                .toList();

        List<CompletableFuture<List<BigDecimal>>> futures = scrapers.stream()
                .map(scraper -> CompletableFuture.supplyAsync(
                        () -> safeFetch(scraper, marca, modelo, ano), executor))
                .toList();

        List<BigDecimal> precos = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .toList();

        return aggregate(precos, linksReferencia);
    }

    private List<BigDecimal> safeFetch(MercadoClientProvider scraper,
                                       String marca, String modelo, int ano) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<BigDecimal> precos = scraper.fetchPrecos(marca, modelo, ano);
            recordOutcome(scraper.providerName(), "success", sample);
            return precos != null ? precos : List.of();
        } catch (Exception ex) {
            recordOutcome(scraper.providerName(), "failure", sample);
            log.warn("Scraper {} failed for {}-{}-{}: {}",
                    scraper.providerName(), marca, modelo, ano, ex.getMessage());
            return List.of();
        }
    }

    private DadosMercado aggregate(List<BigDecimal> precos, List<String> linksReferencia) {
        if (precos.isEmpty()) {
            return new DadosMercado(null, null, null, 0, linksReferencia);
        }
        List<BigDecimal> sorted = new ArrayList<>(precos);
        Collections.sort(sorted);

        BigDecimal menor = sorted.get(0);
        BigDecimal maior = sorted.get(sorted.size() - 1);

        BigDecimal soma = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal media = soma.divide(BigDecimal.valueOf(sorted.size()), 2, RoundingMode.HALF_UP);

        return new DadosMercado(media, menor, maior, sorted.size(), linksReferencia);
    }

    private String computeScore(FipePrecoResponse fipe, DadosMercado mercado) {
        boolean hasFipe = fipe != null && fipe.preco() != null
                && fipe.preco().compareTo(BigDecimal.ZERO) > 0;
        boolean hasMercado = mercado.precoMedio() != null
                && mercado.precoMedio().compareTo(BigDecimal.ZERO) > 0;

        if (!hasFipe && !hasMercado) return SCORE_UNAVAILABLE;
        if (!hasFipe) return SCORE_NO_FIPE;
        if (!hasMercado) return SCORE_NO_MARKET;

        BigDecimal ratio = mercado.precoMedio().divide(fipe.preco(), 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(UPPER_BOUND) > 0) return SCORE_ABOVE;
        if (ratio.compareTo(LOWER_BOUND) < 0) return SCORE_BELOW;
        return SCORE_IN_LINE;
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
