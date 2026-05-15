package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated snapshot of real-world marketplace prices captured by the
 * Avaliação domain through web scraping. Always financial-safe ({@link BigDecimal}).
 *
 * <p>A média bruta ({@code precoMedio}) considera <b>todas</b> as amostras
 * coletadas; o {@code precoMedioAjustado} aplica o motor estatístico
 * Anti-Outlier ({@link EstatisticaAntiOutlier}) e expurga anúncios
 * provavelmente fraudulentos (≤ limite inferior) ou irreais (≥ limite
 * superior). É o {@code precoMedioAjustado} que deve guiar decisões
 * comerciais — o bruto fica exposto para auditoria.</p>
 *
 * <p>Fields may be {@code null} or zero when no listings were extracted from
 * any scraper — that is a legitimate state, not an error. The orchestrator
 * uses {@code quantidadeAnunciosEncontrados == 0} as the signal to mark the
 * score as "Sem dados de mercado".</p>
 *
 * <p><b>Regionalização:</b> quando o chamador informa {@code uf} (e
 * opcionalmente {@code cidade}), os scrapers reescrevem a URL alvo para o
 * recorte geográfico do marketplace e {@code precoMedioRegional} carrega a
 * média da amostra local. Sem {@code uf}, a busca degrada graciosamente para
 * o escopo nacional, {@code uf}/{@code cidade} chegam {@code null} e
 * {@code precoMedioRegional} também — {@code precoMedio} segue sendo a média
 * nacional. O {@link DetalhesAmostragem} explica qual escopo foi aplicado.</p>
 */
@Schema(name = "DadosMercado",
        description = "Snapshot de preços reais coletados via raspagem dos marketplaces, com recorte geográfico opcional e filtro Anti-Outlier.")
public record DadosMercado(
        @Schema(description = "Preço médio bruto (todas as amostras, sem expurgo). Mantido para auditoria — não usar como referência comercial.",
                example = "46980.00")
        BigDecimal precoMedio,

        @Schema(description = "Preço médio **ajustado** pelo motor Anti-Outlier — descartadas amostras com desvio estatístico excessivo. Este é o valor de referência comercial.",
                example = "47620.00")
        BigDecimal precoMedioAjustado,

        @Schema(description = "Menor preço observado entre os anúncios coletados (após filtro Anti-Outlier).",
                example = "44900.00")
        BigDecimal menorPreco,

        @Schema(description = "Maior preço observado entre os anúncios coletados (após filtro Anti-Outlier).",
                example = "51800.00")
        BigDecimal maiorPreco,

        @Schema(description = "Quantidade de anúncios efetivamente extraídos (somatório de todos os scrapers, antes do filtro).",
                example = "27")
        int quantidadeAnunciosEncontrados,

        @Schema(description = "Quantidade de outliers expurgados pelo motor estatístico.", example = "4")
        int amostrasDescartadas,

        @Schema(description = "UF aplicada como filtro geográfico. **null** quando a busca foi nacional.",
                example = "SP", nullable = true)
        String uf,

        @Schema(description = "Cidade aplicada como filtro geográfico. **null** quando não informada.",
                example = "Campinas", nullable = true)
        String cidade,

        @Schema(description = "Preço médio da amostra regional **após o filtro Anti-Outlier**. **null** quando a busca foi nacional (sem `uf`).",
                example = "49120.00", nullable = true)
        BigDecimal precoMedioRegional,

        @Schema(description = "Detalhamento da amostragem geográfica e da composição por fonte.")
        DetalhesAmostragem detalhesAmostragem,

        @Schema(description = "Memória de cálculo do motor estatístico Anti-Outlier (mediana, banda, dispersão).")
        EstatisticaAntiOutlier estatisticaAntiOutlier,

        @Schema(description = "Lista de fontes (marketplaces) consultadas com a volumetria de cada uma.")
        List<FonteConsultada> fontesConsultadas,

        @Schema(description = "URLs das páginas de busca consultadas (uma por marketplace, já regionalizadas quando aplicável). Mantido por compatibilidade — usar `fontesConsultadas` para a visão completa.")
        List<String> linksReferencia
) {
}
