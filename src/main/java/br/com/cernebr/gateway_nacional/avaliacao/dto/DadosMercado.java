package br.com.cernebr.gateway_nacional.avaliacao.dto;

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
 */
@Schema(name = "DadosMercado",
        description = "Snapshot de preços reais coletados via raspagem dos marketplaces.")
public record DadosMercado(
        @Schema(description = "Preço médio dos anúncios coletados", example = "47350.00")
        BigDecimal precoMedio,

        @Schema(description = "Menor preço observado entre os anúncios coletados", example = "42900.00")
        BigDecimal menorPreco,

        @Schema(description = "Maior preço observado entre os anúncios coletados", example = "52800.00")
        BigDecimal maiorPreco,

        @Schema(description = "Quantidade de anúncios efetivamente extraídos (somatório de todos os scrapers)", example = "27")
        int quantidadeAnunciosEncontrados,

        @Schema(description = "URLs das páginas de busca consultadas (uma por marketplace)")
        List<String> linksReferencia
) {
}
