package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.placa.dto.PlacaResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Composite output of the Avaliação domain — glues optional identification
 * data ({@link PlacaResponse}), optional FIPE reference ({@link FipePrecoResponse})
 * and real-market scraping ({@link DadosMercado}) into a single price-gap view.
 *
 * <p>Two entry points feed this record:
 * <ul>
 *   <li>{@code GET /api/v1/avaliacao/placa/{placa}} — full pipeline. {@code placa}
 *       and {@code dadosVeiculo} are populated.</li>
 *   <li>{@code GET /api/v1/avaliacao/manual} — placa lookup is bypassed entirely
 *       (handy when WDApi/Keplaca are blocked or out of credits, but the caller
 *       already knows marca/modelo/ano). {@code placa} and {@code dadosVeiculo}
 *       arrive {@code null} by design.</li>
 * </ul>
 *
 * <p>{@code referenciaFipe} is independently optional in both flows — the gateway
 * does not maintain a placa→codigoFipe mapping, so the caller supplies the
 * code when they want the FIPE comparison. When absent, {@code scoreAvaliacao}
 * reflects that the comparison was skipped.</p>
 */
@Schema(name = "AvaliacaoResponse",
        description = "Avaliação consolidada cruzando (opcionalmente) dados de placa, (opcionalmente) FIPE e mercado real.")
public record AvaliacaoResponse(
        @Schema(description = "Placa normalizada (uppercase, sem hífen). **null** quando a avaliação foi feita via /manual.",
                example = "ABC1D23", nullable = true)
        String placa,

        @Schema(description = "Dados de identificação do veículo retornados pelo módulo Placa. **null** quando a avaliação foi feita via /manual (sem consulta de placa).",
                nullable = true)
        PlacaResponse dadosVeiculo,

        @Schema(description = "Cotação FIPE de referência. **null** quando o `codigoFipe` não foi informado.",
                nullable = true)
        FipePrecoResponse referenciaFipe,

        @Schema(description = "Snapshot de mercado coletado via scraping")
        DadosMercado mercado,

        @Schema(description = "Veredito comparando preço médio de mercado contra FIPE",
                example = "Acima da FIPE")
        String scoreAvaliacao
) {
}
