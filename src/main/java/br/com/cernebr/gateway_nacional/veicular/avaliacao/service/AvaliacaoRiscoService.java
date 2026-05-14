package br.com.cernebr.gateway_nacional.veicular.avaliacao.service;

import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.AvaliacaoCompletaResponse;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.AvaliacaoResponse;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.DadosMercado;
import br.com.cernebr.gateway_nacional.veicular.historico.dto.HistoricoVeicularDTO;
import br.com.cernebr.gateway_nacional.veicular.historico.dto.RiscoConsolidado;
import br.com.cernebr.gateway_nacional.veicular.historico.service.HistoricoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cross-domain orchestrator — the <b>depreciation engine</b>. It crosses the
 * {@link AvaliacaoService} pricing pipeline (Placa + FIPE + marketplace
 * scraping) with the {@link HistoricoService} risk pipeline (leilão /
 * sinistro, premium-first with free-tier fallback) and applies an automatic
 * devaluation on the market price when the vehicle carries risk evidence.
 *
 * <p><b>Business rule:</b>
 * <ul>
 *   <li>{@code BAIXO} — nada consta: market price kept intact, reducer 0%;</li>
 *   <li>{@code MEDIO} — exactly one indicator (leilão XOR sinistro): apply
 *       the {@code redutor-medio} (default 20%);</li>
 *   <li>{@code ALTO}  — leilão AND sinistro: apply the {@code redutor-alto}
 *       (default 30%) — a vehicle flagged for both carries strictly more
 *       devaluation risk.</li>
 * </ul>
 * Both reducer values are configurable via
 * {@code gateway.veicular.risco.redutor-medio} / {@code redutor-alto} and
 * are clamped to the mandated 20%–30% band on startup so a misconfiguration
 * cannot push the depreciation outside the contract.</p>
 *
 * <p><b>Concurrency:</b> the pricing call and the historico call are
 * independent — they run concurrently on a virtual-thread executor, so the
 * wall-time of the integrated route tracks {@code max(avaliacao, historico)}
 * rather than the sum. Both legs are individually fail-soft (the avaliação
 * pipeline degrades scraper-by-scraper, the historico pipeline drops failed
 * fontes), so neither {@code join()} can throw for a recoverable upstream
 * failure.</p>
 */
@Slf4j
@Service
public class AvaliacaoRiscoService {

    /** Hard floor / ceiling of the mandated reducer band. */
    private static final BigDecimal REDUTOR_MIN = new BigDecimal("0.20");
    private static final BigDecimal REDUTOR_MAX = new BigDecimal("0.30");
    private static final BigDecimal SEM_REDUTOR = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final AvaliacaoService avaliacaoService;
    private final HistoricoService historicoService;

    /** Reducer applied on risco MEDIO — clamped to [0.20, 0.30]. */
    private final BigDecimal redutorMedio;
    /** Reducer applied on risco ALTO — clamped to [0.20, 0.30]. */
    private final BigDecimal redutorAlto;

    public AvaliacaoRiscoService(AvaliacaoService avaliacaoService,
                                 HistoricoService historicoService,
                                 @Value("${gateway.veicular.risco.redutor-medio:0.20}") BigDecimal redutorMedio,
                                 @Value("${gateway.veicular.risco.redutor-alto:0.30}") BigDecimal redutorAlto) {
        this.avaliacaoService = avaliacaoService;
        this.historicoService = historicoService;
        this.redutorMedio = clampRedutor(redutorMedio, "redutor-medio");
        this.redutorAlto = clampRedutor(redutorAlto, "redutor-alto");
    }

    /**
     * Clamps a configured reducer into the mandated 20%–30% band. A value
     * below 0.20 or above 0.30 is pulled to the nearest bound and logged —
     * the depreciation contract is non-negotiable, config only tunes within it.
     */
    private static BigDecimal clampRedutor(BigDecimal configured, String prop) {
        if (configured == null) {
            return REDUTOR_MIN;
        }
        BigDecimal clamped = configured.max(REDUTOR_MIN).min(REDUTOR_MAX);
        if (clamped.compareTo(configured) != 0) {
            log.warn("gateway.veicular.risco.{}={} fora da faixa mandatória [0.20, 0.30] — ajustado para {}.",
                    prop, configured, clamped);
        }
        return clamped.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Runs the integrated evaluation for the given placa. The placa is
     * already normalised at the controller boundary (uppercase, no hyphen).
     *
     * @param placa      normalized placa;
     * @param codigoFipe optional FIPE code forwarded to the pricing pipeline;
     * @param uf         optional UF to regionalise the marketplace scraping;
     * @param cidade     optional city to narrow the scraping within the UF.
     */
    public AvaliacaoCompletaResponse avaliarComRisco(String placa, String codigoFipe,
                                                     String uf, String cidade) {
        AvaliacaoResponse avaliacao;
        HistoricoVeicularDTO historico;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<AvaliacaoResponse> avaliacaoFuture =
                    CompletableFuture.supplyAsync(() -> avaliacaoService.avaliarPorPlaca(placa, codigoFipe, uf, cidade), executor);
            CompletableFuture<HistoricoVeicularDTO> historicoFuture =
                    CompletableFuture.supplyAsync(() -> historicoService.consultar(placa), executor);
            avaliacao = avaliacaoFuture.join();
            historico = historicoFuture.join();
        }
        return compor(placa, avaliacao, historico);
    }

    /**
     * Folds the pricing and risk results into the integrated response and
     * applies the depreciation. The base price for the reducer is the
     * marketplace average; when the scrapers produced nothing usable it
     * falls back to the FIPE reference so a risky vehicle still gets a
     * depreciated number instead of a {@code null}.
     */
    private AvaliacaoCompletaResponse compor(String placa,
                                             AvaliacaoResponse avaliacao,
                                             HistoricoVeicularDTO historico) {
        RiscoConsolidado risco = historico.riscoConsolidado() != null
                ? historico.riscoConsolidado()
                : RiscoConsolidado.BAIXO;
        boolean alertaRiscoGrave = risco != RiscoConsolidado.BAIXO;

        BigDecimal precoBase = resolvePrecoBase(avaliacao);
        BigDecimal redutor = redutorParaRisco(risco);
        BigDecimal precoAjustado = aplicarRedutor(precoBase, redutor);

        List<String> apontamentos = extrairApontamentos(historico);

        log.info("Avaliação integrada placa={} risco={} alertaRiscoGrave={} precoBase={} redutor={} precoAjustado={}",
                placa, risco, alertaRiscoGrave, precoBase, redutor, precoAjustado);

        return new AvaliacaoCompletaResponse(
                placa,
                avaliacao.dadosVeiculo(),
                avaliacao.referenciaFipe(),
                avaliacao.mercado(),
                avaliacao.scoreAvaliacao(),
                avaliacao.avaliacaoKbb(),
                risco,
                alertaRiscoGrave,
                apontamentos,
                historico.fontesConsultadas() != null ? historico.fontesConsultadas() : List.of(),
                precoBase,
                redutor,
                precoAjustado
        );
    }

    /**
     * Picks the price the reducer operates on: the marketplace average when
     * the scrapers produced one, otherwise the FIPE reference, otherwise
     * {@code null} (no price could be established — the reducer is a no-op
     * and {@code precoAjustadoRisco} stays {@code null}).
     */
    private BigDecimal resolvePrecoBase(AvaliacaoResponse avaliacao) {
        DadosMercado mercado = avaliacao.mercado();
        if (mercado != null && mercado.precoMedioAjustado() != null
                && mercado.precoMedioAjustado().compareTo(BigDecimal.ZERO) > 0) {
            return mercado.precoMedioAjustado();
        }
        if (mercado != null && mercado.precoMedio() != null
                && mercado.precoMedio().compareTo(BigDecimal.ZERO) > 0) {
            return mercado.precoMedio();
        }
        if (avaliacao.referenciaFipe() != null && avaliacao.referenciaFipe().preco() != null
                && avaliacao.referenciaFipe().preco().compareTo(BigDecimal.ZERO) > 0) {
            return avaliacao.referenciaFipe().preco();
        }
        return null;
    }

    private BigDecimal redutorParaRisco(RiscoConsolidado risco) {
        return switch (risco) {
            case BAIXO -> SEM_REDUTOR;
            case MEDIO -> redutorMedio;
            case ALTO -> redutorAlto;
        };
    }

    /**
     * {@code precoAjustado = precoBase * (1 - redutor)}. Returns {@code null}
     * when no base price could be established. {@code RoundingMode.HALF_UP}
     * with scale 2 keeps the value cent-accurate for downstream credit math.
     */
    private BigDecimal aplicarRedutor(BigDecimal precoBase, BigDecimal redutor) {
        if (precoBase == null) {
            return null;
        }
        BigDecimal fator = BigDecimal.ONE.subtract(redutor);
        return precoBase.multiply(fator).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Explodes the consolidated {@code detalhesLeilao} audit string (the
     * orchestrator joins per-source one-liners with {@code " | "}) back into
     * a list — one apontamento per element. Empty list when nada consta.
     */
    private List<String> extrairApontamentos(HistoricoVeicularDTO historico) {
        String detalhes = historico.detalhesLeilao();
        if (detalhes == null || detalhes.isBlank()) {
            return List.of();
        }
        List<String> apontamentos = new ArrayList<>();
        for (String parte : detalhes.split("\\s\\|\\s")) {
            String limpo = parte.trim();
            if (!limpo.isEmpty()) {
                apontamentos.add(limpo);
            }
        }
        return List.copyOf(apontamentos);
    }
}
