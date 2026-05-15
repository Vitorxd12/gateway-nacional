package br.com.cernebr.gateway_nacional.veicular.avaliacao.service;

import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.EstatisticaAntiOutlier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Motor estatístico Anti-Outlier — expurga anúncios falsos ou irreais da
 * amostra agregada antes do cálculo do preço médio. Substitui a média
 * aritmética bruta por uma estimativa robusta baseada em estatística
 * descritiva.
 *
 * <h2>Algoritmo principal: MAD (Median Absolute Deviation)</h2>
 * <p>O MAD é um estimador robusto de dispersão — diferente do desvio
 * padrão, ele não é arrastado por valores extremos (porque calcula a
 * mediana dos desvios da mediana, não a média dos quadrados). Para uma
 * distribuição aproximadamente normal, vale a relação:
 * <pre>
 *     σ ≈ 1.4826 × MAD
 * </pre>
 * O filtro define como outlier qualquer amostra fora da banda:
 * <pre>
 *     [mediana − k·σ̂ ,  mediana + k·σ̂]
 * </pre>
 * com {@code k} configurável (default {@code 3.0}, equivalente ao 99.7%
 * de cobertura sob normalidade).
 *
 * <h2>Fallback: IQR (Interquartile Range)</h2>
 * <p>Quando o MAD colapsa a zero (mais da metade da amostra tem o mesmo
 * preço — comum em populares com preço-âncora), o serviço cai para o
 * filtro de Tukey via IQR ({@code Q1 − 1.5·IQR, Q3 + 1.5·IQR}). Quando
 * IQR também colapsa, aplica a regra heurística do briefing (±30% sobre
 * a mediana — anúncios 30% mais baratos = provável golpe; 30% mais caros
 * = fora da realidade).
 *
 * <h2>Amostra pequena</h2>
 * <p>Para {@code n < 4}, nenhum filtro estatístico é confiável — o
 * algoritmo entra em modo {@code NENHUM}, devolve a média aritmética e
 * marca {@code amostrasDescartadas=0}. Decisão consciente: melhor entregar
 * dado bruto com aviso do que rejeitar dado legítimo por dispersão
 * espúria.
 */
@Slf4j
@Service
public class EstatisticaVeicularService {

    /** Constante de consistência MAD → σ sob distribuição normal. */
    private static final BigDecimal MAD_TO_SIGMA = new BigDecimal("1.4826");

    /** Heurística do briefing: ±30% sobre a mediana quando MAD/IQR colapsam. */
    private static final BigDecimal FLOOR_RATIO = new BigDecimal("0.70");
    private static final BigDecimal CEILING_RATIO = new BigDecimal("1.30");

    /** Contexto matemático com precisão suficiente para preços de veículos (até R$ 1.5M). */
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    private final BigDecimal madMultiplier;
    private final BigDecimal iqrMultiplier;
    private final int minSampleSize;

    public EstatisticaVeicularService(
            @Value("${gateway.avaliacao.estatistica.mad-multiplier:3.0}") BigDecimal madMultiplier,
            @Value("${gateway.avaliacao.estatistica.iqr-multiplier:1.5}") BigDecimal iqrMultiplier,
            @Value("${gateway.avaliacao.estatistica.min-sample-size:4}") int minSampleSize) {
        this.madMultiplier = madMultiplier;
        this.iqrMultiplier = iqrMultiplier;
        this.minSampleSize = minSampleSize;
    }

    /**
     * Pipeline completo de expurgo. Devolve um {@link AmostraFiltrada}
     * carregando a amostra filtrada (em ordem original), a memória de
     * cálculo (mediana, σ, limites) e a contagem de descartes.
     *
     * <p><b>Determinismo:</b> a função é pura — mesma entrada, mesma
     * saída. Não toca em estado compartilhado, então pode rodar em
     * paralelo no fan-out do orquestrador.</p>
     */
    public AmostraFiltrada filtrar(List<BigDecimal> precos) {
        if (precos == null || precos.isEmpty()) {
            return AmostraFiltrada.vazia();
        }
        List<BigDecimal> input = new ArrayList<>();
        for (BigDecimal p : precos) {
            if (p != null && p.signum() > 0) {
                input.add(p);
            }
        }
        if (input.isEmpty()) {
            return AmostraFiltrada.vazia();
        }
        if (input.size() < minSampleSize) {
            return amostraInsuficiente(input);
        }

        List<BigDecimal> sorted = new ArrayList<>(input);
        Collections.sort(sorted);

        BigDecimal mediana = mediana(sorted);
        BigDecimal desvio = desvioPadrao(sorted, media(sorted));

        // 1ª tentativa — MAD.
        BigDecimal mad = mad(sorted, mediana);
        if (mad.signum() > 0) {
            BigDecimal sigmaHat = MAD_TO_SIGMA.multiply(mad, MC);
            BigDecimal banda = sigmaHat.multiply(madMultiplier, MC);
            BigDecimal limiteInf = mediana.subtract(banda, MC).max(BigDecimal.ZERO);
            BigDecimal limiteSup = mediana.add(banda, MC);
            return aplicarFiltro(input, sorted, mediana, desvio,
                    limiteInf, limiteSup, EstatisticaAntiOutlier.ALGORITMO_MAD);
        }

        // 2ª tentativa — IQR de Tukey.
        BigDecimal q1 = percentil(sorted, 0.25);
        BigDecimal q3 = percentil(sorted, 0.75);
        BigDecimal iqr = q3.subtract(q1, MC);
        if (iqr.signum() > 0) {
            BigDecimal margem = iqr.multiply(iqrMultiplier, MC);
            BigDecimal limiteInf = q1.subtract(margem, MC).max(BigDecimal.ZERO);
            BigDecimal limiteSup = q3.add(margem, MC);
            return aplicarFiltro(input, sorted, mediana, desvio,
                    limiteInf, limiteSup, EstatisticaAntiOutlier.ALGORITMO_IQR);
        }

        // 3ª tentativa — heurística ±30% da mediana (regra do briefing).
        BigDecimal limiteInf = mediana.multiply(FLOOR_RATIO, MC);
        BigDecimal limiteSup = mediana.multiply(CEILING_RATIO, MC);
        return aplicarFiltro(input, sorted, mediana, desvio,
                limiteInf, limiteSup, EstatisticaAntiOutlier.ALGORITMO_DESVIO_PADRAO);
    }

    private AmostraFiltrada amostraInsuficiente(List<BigDecimal> input) {
        List<BigDecimal> sorted = new ArrayList<>(input);
        Collections.sort(sorted);
        BigDecimal mediana = mediana(sorted);
        BigDecimal media = media(sorted);
        BigDecimal desvio = desvioPadrao(sorted, media);
        EstatisticaAntiOutlier stats = new EstatisticaAntiOutlier(
                EstatisticaAntiOutlier.ALGORITMO_NENHUM,
                round2(mediana), round2(mediana),
                round2(desvio), round2(desvio),
                null, null,
                0, input.size(),
                coeficienteVariacao(desvio, media));
        log.info("Anti-Outlier: amostra com {} elementos (< {}), filtro pulado.", input.size(), minSampleSize);
        return new AmostraFiltrada(input, stats);
    }

    private AmostraFiltrada aplicarFiltro(List<BigDecimal> original,
                                          List<BigDecimal> sorted,
                                          BigDecimal mediana,
                                          BigDecimal desvioOriginal,
                                          BigDecimal limiteInf,
                                          BigDecimal limiteSup,
                                          String algoritmo) {
        List<BigDecimal> filtrada = new ArrayList<>(original.size());
        int descartados = 0;
        for (BigDecimal v : original) {
            if (v.compareTo(limiteInf) >= 0 && v.compareTo(limiteSup) <= 0) {
                filtrada.add(v);
            } else {
                descartados++;
            }
        }
        // Se o filtro derrubar tudo (caso patológico — todos pontos extremos),
        // recolhe os mais próximos da mediana via amostra original.
        if (filtrada.isEmpty()) {
            log.warn("Anti-Outlier ({}): banda [{}, {}] zerou a amostra (n={}). Mantendo amostra bruta.",
                    algoritmo, limiteInf, limiteSup, original.size());
            BigDecimal media = media(sorted);
            EstatisticaAntiOutlier stats = new EstatisticaAntiOutlier(
                    EstatisticaAntiOutlier.ALGORITMO_NENHUM,
                    round2(mediana), round2(mediana),
                    round2(desvioOriginal), round2(desvioOriginal),
                    null, null,
                    0, original.size(),
                    coeficienteVariacao(desvioOriginal, media));
            return new AmostraFiltrada(original, stats);
        }

        List<BigDecimal> sortedFiltrada = new ArrayList<>(filtrada);
        Collections.sort(sortedFiltrada);
        BigDecimal medianaAjustada = mediana(sortedFiltrada);
        BigDecimal mediaAjustada = media(sortedFiltrada);
        BigDecimal desvioAjustado = desvioPadrao(sortedFiltrada, mediaAjustada);

        EstatisticaAntiOutlier stats = new EstatisticaAntiOutlier(
                algoritmo,
                round2(mediana), round2(medianaAjustada),
                round2(desvioOriginal), round2(desvioAjustado),
                round2(limiteInf), round2(limiteSup),
                descartados, filtrada.size(),
                coeficienteVariacao(desvioAjustado, mediaAjustada));

        log.info("Anti-Outlier ({}): mediana={}, banda=[{}, {}], descartados={}/{} amostras.",
                algoritmo, round2(mediana), round2(limiteInf), round2(limiteSup),
                descartados, original.size());
        return new AmostraFiltrada(filtrada, stats);
    }

    /** Mediana clássica — para n par, média dos dois centrais. */
    private static BigDecimal mediana(List<BigDecimal> sorted) {
        int n = sorted.size();
        if (n == 0) return BigDecimal.ZERO;
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        BigDecimal a = sorted.get(n / 2 - 1);
        BigDecimal b = sorted.get(n / 2);
        return a.add(b, MC).divide(BigDecimal.valueOf(2), MC);
    }

    /** Média aritmética. */
    private static BigDecimal media(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(v, MC);
        return sum.divide(BigDecimal.valueOf(values.size()), MC);
    }

    /** Desvio padrão amostral (n-1 no denominador). */
    private static BigDecimal desvioPadrao(List<BigDecimal> values, BigDecimal mean) {
        int n = values.size();
        if (n < 2) return BigDecimal.ZERO;
        BigDecimal acc = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal diff = v.subtract(mean, MC);
            acc = acc.add(diff.multiply(diff, MC), MC);
        }
        BigDecimal variance = acc.divide(BigDecimal.valueOf(n - 1L), MC);
        return sqrt(variance);
    }

    /**
     * Median Absolute Deviation — mediana dos desvios absolutos da mediana.
     * Robusta a outliers (breakdown point 50%).
     */
    private static BigDecimal mad(List<BigDecimal> sorted, BigDecimal median) {
        List<BigDecimal> desvios = new ArrayList<>(sorted.size());
        for (BigDecimal v : sorted) {
            desvios.add(v.subtract(median, MC).abs());
        }
        Collections.sort(desvios);
        return mediana(desvios);
    }

    /**
     * Percentil pela interpolação linear (R-7 / Excel — mesma convenção
     * adotada pelo Apache Commons Math em {@code Percentile.LEGACY}).
     */
    private static BigDecimal percentil(List<BigDecimal> sorted, double p) {
        int n = sorted.size();
        if (n == 0) return BigDecimal.ZERO;
        if (n == 1) return sorted.get(0);
        double h = (n - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = (int) Math.ceil(h);
        if (lo == hi) return sorted.get(lo);
        BigDecimal frac = BigDecimal.valueOf(h - lo);
        BigDecimal a = sorted.get(lo);
        BigDecimal b = sorted.get(hi);
        return a.add(b.subtract(a, MC).multiply(frac, MC), MC);
    }

    /** Raiz quadrada com {@link MathContext} controlado — disponível desde Java 9. */
    private static BigDecimal sqrt(BigDecimal x) {
        if (x.signum() <= 0) return BigDecimal.ZERO;
        return x.sqrt(MC);
    }

    private static BigDecimal coeficienteVariacao(BigDecimal sigma, BigDecimal mu) {
        if (mu == null || mu.signum() == 0) return null;
        return sigma.divide(mu, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Resultado do filtro: amostra remanescente + memória de cálculo
     * estatístico. Imutável por contrato — record + lista defensivamente
     * copiada na construção do orquestrador.
     */
    public record AmostraFiltrada(
            List<BigDecimal> amostraFiltrada,
            EstatisticaAntiOutlier estatistica) {

        public static AmostraFiltrada vazia() {
            EstatisticaAntiOutlier stats = new EstatisticaAntiOutlier(
                    EstatisticaAntiOutlier.ALGORITMO_NENHUM,
                    null, null, null, null, null, null,
                    0, 0, null);
            return new AmostraFiltrada(List.of(), stats);
        }
    }
}
