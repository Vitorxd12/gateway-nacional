package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated snapshot of real-world marketplace prices captured by the
 * Avaliação domain through web scraping. Always financial-safe ({@link BigDecimal}).
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
        description = "Snapshot de preços reais coletados via raspagem dos marketplaces, com recorte geográfico opcional.")
public record DadosMercado(
        @Schema(description = "Preço médio de todos os anúncios coletados (nacional ou regional, conforme o escopo).",
                example = "47350.00")
        BigDecimal precoMedio,

        @Schema(description = "Menor preço observado entre os anúncios coletados", example = "42900.00")
        BigDecimal menorPreco,

        @Schema(description = "Maior preço observado entre os anúncios coletados", example = "52800.00")
        BigDecimal maiorPreco,

        @Schema(description = "Quantidade de anúncios efetivamente extraídos (somatório de todos os scrapers)", example = "27")
        int quantidadeAnunciosEncontrados,

        @Schema(description = "UF aplicada como filtro geográfico. **null** quando a busca foi nacional.",
                example = "SP", nullable = true)
        String uf,

        @Schema(description = "Cidade aplicada como filtro geográfico. **null** quando não informada.",
                example = "Campinas", nullable = true)
        String cidade,

        @Schema(description = "Preço médio da amostra regional. **null** quando a busca foi nacional (sem `uf`).",
                example = "49120.00", nullable = true)
        BigDecimal precoMedioRegional,

        @Schema(description = "Detalhamento da amostragem geográfica que originou os preços.")
        DetalhesAmostragem detalhesAmostragem,

        @Schema(description = "URLs das páginas de busca consultadas (uma por marketplace, já regionalizadas quando aplicável)")
        List<String> linksReferencia
) {
}
