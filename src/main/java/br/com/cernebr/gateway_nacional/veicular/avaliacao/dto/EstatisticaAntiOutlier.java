package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Resultado do motor estatístico Anti-Outlier aplicado sobre a amostra
 * agregada de preços coletados nos marketplaces. Carrega a mediana, o
 * desvio padrão, os limites inferior/superior calculados via MAD (Median
 * Absolute Deviation) ou IQR, e o redutor aplicado.
 *
 * <p>O consumidor da API (front-end Inspetor) usa este bloco para
 * justificar ao lojista <i>como</i> o {@code precoMedioAjustado} foi
 * derivado — quais anúncios foram descartados, qual a banda de
 * confiança, quão dispersa estava a amostra original.</p>
 */
@Schema(name = "EstatisticaAntiOutlier",
        description = "Detalhamento do filtro estatístico Anti-Outlier aplicado sobre a amostra agregada.")
public record EstatisticaAntiOutlier(
        @Schema(description = "Algoritmo aplicado para detecção de outliers.",
                example = "MAD",
                allowableValues = {"MAD", "IQR", "DESVIO_PADRAO", "NENHUM"})
        String algoritmo,

        @Schema(description = "Mediana da amostra original (antes do corte).", example = "47350.00")
        BigDecimal medianaOriginal,

        @Schema(description = "Mediana da amostra após o corte de outliers.", example = "47620.00")
        BigDecimal medianaAjustada,

        @Schema(description = "Desvio padrão amostral da amostra original.", example = "5230.45")
        BigDecimal desvioPadraoOriginal,

        @Schema(description = "Desvio padrão amostral da amostra após o corte.", example = "2410.18")
        BigDecimal desvioPadraoAjustado,

        @Schema(description = "Limite inferior aceito pelo filtro (anúncios abaixo são descartados como prováveis golpes).",
                example = "38800.00")
        BigDecimal limiteInferior,

        @Schema(description = "Limite superior aceito pelo filtro (anúncios acima são descartados como fora-da-realidade).",
                example = "58200.00")
        BigDecimal limiteSuperior,

        @Schema(description = "Quantidade total de amostras descartadas como outliers.", example = "4")
        int amostrasDescartadas,

        @Schema(description = "Quantidade de amostras consideradas no cálculo do `precoMedioAjustado`.", example = "21")
        int amostrasConsideradas,

        @Schema(description = "Coeficiente de variação da amostra ajustada (CV = σ / μ). Quanto menor, mais coesa a amostra.",
                example = "0.0506")
        BigDecimal coeficienteVariacao
) {

    public static final String ALGORITMO_MAD = "MAD";
    public static final String ALGORITMO_IQR = "IQR";
    public static final String ALGORITMO_DESVIO_PADRAO = "DESVIO_PADRAO";
    public static final String ALGORITMO_NENHUM = "NENHUM";
}
