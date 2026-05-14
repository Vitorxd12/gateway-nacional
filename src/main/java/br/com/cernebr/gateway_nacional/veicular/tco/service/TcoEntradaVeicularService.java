package br.com.cernebr.gateway_nacional.veicular.tco.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.service.FipeService;
import br.com.cernebr.gateway_nacional.veicular.tco.dto.AliquotaUfEntry;
import br.com.cernebr.gateway_nacional.veicular.tco.dto.TcoEntradaVeicularDTO;
import br.com.cernebr.gateway_nacional.veicular.tco.repository.AliquotaIpvaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Motor de cálculo financeiro do "Custo Total de Entrada" (TCO) veicular —
 * orquestra o cruzamento cross-domain entre a cotação FIPE (domínio Veicular)
 * e a malha fiscal estadual (domínio Fiscal/Cadastral).
 *
 * <h2>Pipeline matemático</h2>
 * <ol>
 *   <li>Consome o valor exato da Tabela FIPE via {@link FipeService};</li>
 *   <li>Resolve a alíquota de IPVA da UF no {@link AliquotaIpvaRepository}
 *       (com malha de fallback para a alíquota modal nacional de 3%);</li>
 *   <li>{@code estimativaIpvaAnual = valorFipe × alíquota};</li>
 *   <li>{@code custoTotalEntrada = estimativaIpvaAnual + taxaTransferencia}.</li>
 * </ol>
 *
 * <p>Toda aritmética roda em {@link BigDecimal} com {@code RoundingMode.HALF_UP}
 * e escala 2 — o resultado é monetário e alimenta simulações de compra, então
 * o drift de {@code double} é inaceitável.</p>
 *
 * <h2>Cache</h2>
 * <p>{@link RefreshAheadCache} sobre o cache {@code tcoEntradaVeicular}
 * (hard-TTL 30 dias, soft-TTL 15 dias). Alíquotas estaduais e taxas de Detran
 * viram apenas uma vez ao ano (lei orçamentária estadual), e o valor FIPE
 * subjacente já tem seu próprio cache de 15 dias — um TTL longo aqui é seguro
 * e elimina o recálculo no hot path. A chave combina {@code fipeCode + uf +
 * anoModelo} para que UFs e anos distintos nunca colidam.</p>
 */
@Slf4j
@Service
public class TcoEntradaVeicularService {

    private static final String CACHE_NAME = "tcoEntradaVeicular";
    private static final Duration CACHE_SOFT_TTL = Duration.ofDays(15);

    /** Sentinela FIPE para "Zero KM" — evitado ao eleger o ano mais recente do histórico. */
    private static final int ANO_ZERO_KM = 32000;

    private static final int ESCALA_MONETARIA = 2;

    private final FipeService fipeService;
    private final AliquotaIpvaRepository aliquotaRepository;
    private final RefreshAheadCache cache;

    public TcoEntradaVeicularService(FipeService fipeService,
                                     AliquotaIpvaRepository aliquotaRepository,
                                     RefreshAheadCache cache) {
        this.fipeService = fipeService;
        this.aliquotaRepository = aliquotaRepository;
        this.cache = cache;
    }

    /**
     * Calcula o Custo Total de Entrada para um código FIPE numa UF.
     *
     * @param codigoFipe código FIPE no padrão {@code 000000-0}
     * @param uf         sigla da UF (2 letras); já normalizada no controller
     * @param anoModelo  ano modelo opcional. Se {@code null}, o serviço elege
     *                   automaticamente o ano mais recente do histórico FIPE
     *                   do código (ignorando o sentinela Zero KM quando há
     *                   alternativa).
     */
    public TcoEntradaVeicularDTO calcular(String codigoFipe, String uf, @Nullable String anoModelo) {
        String ufNormalizada = uf.trim().toUpperCase(Locale.ROOT);
        String chaveAno = anoModelo == null ? "latest" : anoModelo;
        String cacheKey = codigoFipe + ":" + ufNormalizada + ":" + chaveAno;
        return cache.get(CACHE_NAME, cacheKey, CACHE_SOFT_TTL,
                () -> doCalcular(codigoFipe, ufNormalizada, anoModelo));
    }

    private TcoEntradaVeicularDTO doCalcular(String codigoFipe, String uf, @Nullable String anoModelo) {
        FipePrecoResponse fipe = resolverFipe(codigoFipe, anoModelo);

        AliquotaUfEntry canonica = aliquotaRepository.findByUf(uf);
        boolean fallbackAplicado = canonica == null;
        AliquotaUfEntry entry = fallbackAplicado ? aliquotaRepository.fallbackEntry(uf) : canonica;

        BigDecimal valorFipe = nonNull(fipe.preco()).setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);
        BigDecimal aliquota = nonNull(entry.aliquotaIpva());
        BigDecimal taxaTransferencia = nonNull(entry.taxaTransferencia())
                .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

        BigDecimal estimativaIpvaAnual = valorFipe.multiply(aliquota)
                .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);
        BigDecimal custoTotalEntrada = estimativaIpvaAnual.add(taxaTransferencia)
                .setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP);

        if (fallbackAplicado) {
            log.warn("TCO: UF '{}' fora da base canônica — aplicado fallback modal nacional (3%) para FIPE {}.",
                    uf, codigoFipe);
        }
        log.info("TCO calculado fipe={} uf={} valorFipe={} aliquota={} ipvaAnual={} custoTotalEntrada={} fallback={}",
                codigoFipe, uf, valorFipe, aliquota, estimativaIpvaAnual, custoTotalEntrada, fallbackAplicado);

        return new TcoEntradaVeicularDTO(
                fipe.codigoFipe() != null ? fipe.codigoFipe() : codigoFipe,
                fipe.marca(),
                fipe.modelo(),
                fipe.anoModelo(),
                fipe.mesReferencia(),
                valorFipe,
                uf,
                aliquota,
                estimativaIpvaAnual,
                taxaTransferencia,
                custoTotalEntrada,
                fallbackAplicado
        );
    }

    /**
     * Resolve a cotação FIPE. Com {@code anoModelo} explícito vai direto ao
     * {@link FipeService#findPreco}; sem ele, busca o histórico completo do
     * código e elege o ano mais recente disponível.
     */
    private FipePrecoResponse resolverFipe(String codigoFipe, @Nullable String anoModelo) {
        if (anoModelo != null) {
            return fipeService.findPreco(codigoFipe, anoModelo);
        }
        List<FipePrecoResponse> historico = fipeService.listHistorico(codigoFipe);
        if (historico == null || historico.isEmpty()) {
            throw new ResourceUnavailableException("fipe",
                    "Histórico FIPE vazio para " + codigoFipe + " — impossível estimar o TCO de entrada.");
        }
        // Prefere o ano-modelo mais recente que NÃO seja o sentinela Zero KM;
        // se o histórico só tiver Zero KM, cai nele mesmo.
        return historico.stream()
                .filter(p -> p.anoModelo() != ANO_ZERO_KM)
                .max(Comparator.comparingInt(FipePrecoResponse::anoModelo))
                .orElseGet(() -> historico.get(0));
    }

    /** Auto-cura: trata {@code null} de preço/alíquota/taxa como zero para nunca estourar NPE no motor. */
    private static BigDecimal nonNull(@Nullable BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
