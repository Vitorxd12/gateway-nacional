package br.com.cernebr.gateway_nacional.veicular.avaliacao.service;

import br.com.cernebr.gateway_nacional.veicular.avaliacao.client.MercadoClientProvider;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.AvaliacaoResponse;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.DadosMercado;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.DetalhesAmostragem;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.EstatisticaAntiOutlier;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.FonteConsultada;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.PrecoKbbDTO;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.service.EstatisticaVeicularService.AmostraFiltrada;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.service.FipeService;
import br.com.cernebr.gateway_nacional.veicular.placa.dto.PlacaResponse;
import br.com.cernebr.gateway_nacional.veicular.placa.service.PlacaService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates the cross-domain valuation: Placa identification + FIPE
 * reference + real-market scraping (OLX, MobiAuto, Webmotors, MercadoLivre).
 * The FIPE call and the marketplace fan-out run concurrently on a single
 * virtual-thread executor, so total wall-time tracks the slowest single
 * provider rather than the sum.
 *
 * <h2>Hard timeout & fail-soft</h2>
 * <p>Cada scraper roda em sua própria virtual thread e está sujeito a um
 * hard timeout configurável ({@code gateway.avaliacao.scraper-hard-timeout-millis},
 * default 12s). Se um marketplace travar, o orquestrador apenas <i>ignora</i>
 * o resultado dele e agrega o que sobreviveu — a regra é entregar HTTP 200
 * com a média limpa dos scrapers que responderam. Cada falha (TIMEOUT, FALHA,
 * VAZIO) é registrada em {@link FonteConsultada} para auditoria.</p>
 *
 * <h2>Motor estatístico Anti-Outlier</h2>
 * <p>Após o fan-out, a amostra agregada é passada ao
 * {@link EstatisticaVeicularService}. O serviço aplica filtro robusto
 * (MAD com fallback IQR / heurística ±30%) e devolve a amostra
 * <i>filtrada</i> + a memória de cálculo. O {@code precoMedioAjustado}
 * resultante é o número de referência comercial; o {@code precoMedio}
 * bruto fica exposto para auditoria.</p>
 *
 * <p><b>ATENÇÃO: Não migrar para
 * {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor} nem
 * {@link br.com.cernebr.gateway_nacional.config.RefreshAheadCache}.</b>
 * Orquestrador de múltiplos scrapers Selenium/FlareSolverr.
 * O fan-out interno em virtual threads já paraleliza scrapers independentes;
 * adicionar HedgedExecutor disparia replicação dos scrapers, e RAC duplicaria
 * o trabalho em background. A composição atual é a forma estável de proteger
 * a infraestrutura sem perder concorrência.</p>
 *
 * <p><b>FIPE auto-discovery</b> — when the placa cascade resolves through
 * the {@code PlacaFipeScraperClient}, the {@link PlacaResponse} carries the
 * {@code codigoFipe} associated with that placa. This service detects the
 * field, plugs it straight into the FIPE lookup, and the response shows up
 * with the comparative reference in place — the caller never needed to
 * supply the code by hand.</p>
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
    private final KbbAvaliacaoService kbbAvaliacaoService;
    private final EstatisticaVeicularService estatisticaVeicularService;
    private final MeterRegistry meterRegistry;
    private final long scraperHardTimeoutMillis;

    public AvaliacaoService(PlacaService placaService,
                            FipeService fipeService,
                            List<MercadoClientProvider> scrapers,
                            KbbAvaliacaoService kbbAvaliacaoService,
                            EstatisticaVeicularService estatisticaVeicularService,
                            MeterRegistry meterRegistry,
                            @Value("${gateway.avaliacao.scraper-hard-timeout-millis:12000}") long scraperHardTimeoutMillis) {
        this.placaService = placaService;
        this.fipeService = fipeService;
        this.scrapers = List.copyOf(scrapers);
        this.kbbAvaliacaoService = kbbAvaliacaoService;
        this.estatisticaVeicularService = estatisticaVeicularService;
        this.meterRegistry = meterRegistry;
        this.scraperHardTimeoutMillis = scraperHardTimeoutMillis;
    }

    public AvaliacaoResponse avaliarPorPlaca(String placa, String codigoFipe, String uf, String cidade) {
        PlacaResponse dadosVeiculo = placaService.findByPlaca(placa);
        int ano = dadosVeiculo.anoModelo() > 0 ? dadosVeiculo.anoModelo() : dadosVeiculo.anoFabricacao();
        String effectiveFipe = pickEffectiveCodigoFipe(codigoFipe, dadosVeiculo.codigoFipe());
        return composeAvaliacao(
                dadosVeiculo.placa(),
                dadosVeiculo,
                dadosVeiculo.marca(),
                dadosVeiculo.modelo(),
                ano,
                effectiveFipe,
                uf,
                cidade
        );
    }

    private static String pickEffectiveCodigoFipe(String fromCaller, String fromPlaca) {
        if (fromCaller != null && !fromCaller.isBlank()) return fromCaller;
        if (fromPlaca != null && !fromPlaca.isBlank()) return fromPlaca;
        return null;
    }

    public AvaliacaoResponse avaliarPorVeiculo(String marca, String modelo, int ano,
                                               String codigoFipe, String uf, String cidade) {
        return composeAvaliacao(null, null, marca, modelo, ano, codigoFipe, uf, cidade);
    }

    private AvaliacaoResponse composeAvaliacao(String placa,
                                               PlacaResponse dadosVeiculo,
                                               String marca,
                                               String modelo,
                                               int ano,
                                               String codigoFipe,
                                               String uf,
                                               String cidade) {
        FipePrecoResponse referenciaFipe;
        DadosMercado mercado;
        PrecoKbbDTO avaliacaoKbb;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<FipePrecoResponse> fipeFuture =
                    CompletableFuture.supplyAsync(() -> fetchFipeIfPossible(codigoFipe, ano), executor);
            CompletableFuture<DadosMercado> mercadoFuture =
                    CompletableFuture.supplyAsync(() -> scrapeMercadoOn(marca, modelo, ano, uf, cidade, executor), executor);
            CompletableFuture<PrecoKbbDTO> kbbFuture =
                    CompletableFuture.supplyAsync(() -> fetchKbbIfPossible(codigoFipe, marca, modelo, ano), executor);
            referenciaFipe = fipeFuture.join();
            mercado = mercadoFuture.join();
            avaliacaoKbb = kbbFuture.join();
        }
        String score = computeScore(referenciaFipe, mercado);
        return new AvaliacaoResponse(placa, dadosVeiculo, referenciaFipe, mercado, score, avaliacaoKbb);
    }

    private PrecoKbbDTO fetchKbbIfPossible(String codigoFipe, String marca, String modelo, int anoModelo) {
        try {
            return kbbAvaliacaoService.fetchAvaliacao(codigoFipe, marca, modelo, anoModelo);
        } catch (Exception ex) {
            log.warn("KBB falhou inesperadamente para codigoFipe={} ano={}: {}", codigoFipe, anoModelo, ex.toString());
            return PrecoKbbDTO.indisponivel(codigoFipe, null,
                    "Avaliação KBB indisponível: " + ex.getClass().getSimpleName());
        }
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
     * Fans out to every scraper in parallel — cada chamada com hard timeout
     * via {@link CompletableFuture#orTimeout(long, TimeUnit)}. Resultados
     * são compilados em {@link ScraperOutcome}: status + lista de preços +
     * URL de referência. O orquestrador agrega as listas sobreviventes,
     * passa pelo motor Anti-Outlier e devolve o {@link DadosMercado} final.
     */
    private DadosMercado scrapeMercadoOn(String marca, String modelo, int ano,
                                         String uf, String cidade, ExecutorService executor) {
        List<CompletableFuture<ScraperOutcome>> futures = new ArrayList<>(scrapers.size());
        for (MercadoClientProvider scraper : scrapers) {
            CompletableFuture<ScraperOutcome> future = CompletableFuture
                    .supplyAsync(() -> safeFetch(scraper, marca, modelo, ano, uf, cidade), executor)
                    .orTimeout(scraperHardTimeoutMillis, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        String status = cause instanceof TimeoutException
                                ? FonteConsultada.STATUS_TIMEOUT
                                : FonteConsultada.STATUS_FALHA;
                        log.warn("Scraper {} excedeu hard-timeout/falhou: {}",
                                scraper.providerName(), cause.toString());
                        return ScraperOutcome.failed(scraper, marca, modelo, ano, uf, cidade, status);
                    });
            futures.add(future);
        }

        List<ScraperOutcome> outcomes = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        return aggregate(outcomes, uf, cidade);
    }

    private ScraperOutcome safeFetch(MercadoClientProvider scraper,
                                     String marca, String modelo, int ano,
                                     String uf, String cidade) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String url = scraper.buildSearchUrl(marca, modelo, ano, uf, cidade);
        try {
            List<BigDecimal> precos = scraper.fetchPrecos(marca, modelo, ano, uf, cidade);
            if (precos == null || precos.isEmpty()) {
                recordOutcome(scraper.providerName(), "vazio", sample);
                return new ScraperOutcome(scraper.providerName(),
                        FonteConsultada.STATUS_VAZIO, List.of(), url);
            }
            recordOutcome(scraper.providerName(), "success", sample);
            return new ScraperOutcome(scraper.providerName(),
                    FonteConsultada.STATUS_OK, precos, url);
        } catch (Exception ex) {
            recordOutcome(scraper.providerName(), "failure", sample);
            log.warn("Scraper {} failed for {}-{}-{} [uf={} cidade={}]: {}",
                    scraper.providerName(), marca, modelo, ano, uf, cidade, ex.getMessage());
            return new ScraperOutcome(scraper.providerName(),
                    FonteConsultada.STATUS_FALHA, List.of(), url);
        }
    }

    /**
     * Folds the per-scraper outcomes into a {@link DadosMercado}. Aggregates
     * the surviving samples, runs them through the Anti-Outlier engine, and
     * derives both raw and adjusted averages alongside the per-source volumetry.
     */
    private DadosMercado aggregate(List<ScraperOutcome> outcomes, String uf, String cidade) {
        boolean regional = uf != null && !uf.isBlank();
        String ufOut = regional ? uf : null;
        String cidadeOut = (regional && cidade != null && !cidade.isBlank()) ? cidade : null;

        List<BigDecimal> precosAgregados = new ArrayList<>();
        for (ScraperOutcome o : outcomes) {
            precosAgregados.addAll(o.precos());
        }
        int totalBruto = precosAgregados.size();

        AmostraFiltrada filtrada = estatisticaVeicularService.filtrar(precosAgregados);
        Set<BigDecimal> sobreviventes = new HashSet<>(filtrada.amostraFiltrada());
        EstatisticaAntiOutlier estatistica = filtrada.estatistica();

        // Volumetria por fonte — também conta quantos outliers cada player contribuiu.
        List<FonteConsultada> fontes = new ArrayList<>(outcomes.size());
        for (ScraperOutcome o : outcomes) {
            int descartados = (int) o.precos().stream().filter(p -> !sobreviventes.contains(p)).count();
            fontes.add(new FonteConsultada(
                    o.fonte(), o.status(), o.precos().size(), descartados, o.linkReferencia()));
        }

        int scrapersConsultados = outcomes.size();
        int scrapersComRetorno = (int) outcomes.stream()
                .filter(o -> FonteConsultada.STATUS_OK.equals(o.status()))
                .count();

        List<String> linksReferencia = outcomes.stream().map(ScraperOutcome::linkReferencia).toList();

        if (filtrada.amostraFiltrada().isEmpty()) {
            DetalhesAmostragem vazia = new DetalhesAmostragem(
                    DetalhesAmostragem.escopo(ufOut, cidadeOut), 0, scrapersConsultados, scrapersComRetorno);
            return new DadosMercado(null, null, null, null,
                    totalBruto, estatistica.amostrasDescartadas(),
                    ufOut, cidadeOut, null,
                    vazia, estatistica, fontes, linksReferencia);
        }

        // Estatísticas finais sobre a amostra filtrada.
        List<BigDecimal> sortedFiltrada = new ArrayList<>(filtrada.amostraFiltrada());
        Collections.sort(sortedFiltrada);
        BigDecimal menor = sortedFiltrada.get(0);
        BigDecimal maior = sortedFiltrada.get(sortedFiltrada.size() - 1);

        // Média bruta (com todos os anúncios, antes do expurgo) — fica exposta para auditoria.
        BigDecimal mediaBruta = mediaDe(precosAgregados);
        BigDecimal mediaAjustada = mediaDe(filtrada.amostraFiltrada());

        BigDecimal mediaRegional = regional ? mediaAjustada : null;
        DetalhesAmostragem detalhes = new DetalhesAmostragem(
                DetalhesAmostragem.escopo(ufOut, cidadeOut),
                filtrada.amostraFiltrada().size(),
                scrapersConsultados, scrapersComRetorno);

        return new DadosMercado(mediaBruta, mediaAjustada, menor, maior,
                totalBruto, estatistica.amostrasDescartadas(),
                ufOut, cidadeOut, mediaRegional,
                detalhes, estatistica, fontes, linksReferencia);
    }

    private static BigDecimal mediaDe(List<BigDecimal> precos) {
        if (precos == null || precos.isEmpty()) return null;
        BigDecimal soma = precos.stream()
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return soma.divide(BigDecimal.valueOf(precos.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Comparison uses {@code precoMedioAjustado} (the Anti-Outlier output);
     * fallback to {@code precoMedio} (raw) when adjusted is null — happens
     * only when there were no samples at all.
     */
    private String computeScore(FipePrecoResponse fipe, DadosMercado mercado) {
        BigDecimal mercadoBase = mercado.precoMedioAjustado() != null
                ? mercado.precoMedioAjustado()
                : mercado.precoMedio();

        boolean hasFipe = fipe != null && fipe.preco() != null
                && fipe.preco().compareTo(BigDecimal.ZERO) > 0;
        boolean hasMercado = mercadoBase != null
                && mercadoBase.compareTo(BigDecimal.ZERO) > 0;

        if (!hasFipe && !hasMercado) return SCORE_UNAVAILABLE;
        if (!hasFipe) return SCORE_NO_FIPE;
        if (!hasMercado) return SCORE_NO_MARKET;

        BigDecimal ratio = mercadoBase.divide(fipe.preco(), 4, RoundingMode.HALF_UP);
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

    /**
     * Resultado granular de uma chamada a um scraper. Preserva o
     * {@code linkReferencia} (URL exata raspada) e o status macro para
     * compor {@link FonteConsultada} sem reconsultar nada.
     */
    private record ScraperOutcome(
            String fonte,
            String status,
            List<BigDecimal> precos,
            String linkReferencia) {

        static ScraperOutcome failed(MercadoClientProvider scraper,
                                     String marca, String modelo, int ano,
                                     String uf, String cidade, String status) {
            String url;
            try {
                url = scraper.buildSearchUrl(marca, modelo, ano, uf, cidade);
            } catch (Exception ex) {
                url = "";
            }
            return new ScraperOutcome(scraper.providerName(), status, List.of(), url);
        }
    }
}
