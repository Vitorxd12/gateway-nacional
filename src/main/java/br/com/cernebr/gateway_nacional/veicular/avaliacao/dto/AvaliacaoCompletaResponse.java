package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.historico.dto.RiscoConsolidado;
import br.com.cernebr.gateway_nacional.veicular.placa.dto.PlacaResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Composite output of the <b>Avaliação e Risco Integrado</b> route — the
 * cross-domain product that crosses the {@code Avaliação} pricing pipeline
 * (Placa + FIPE + marketplace scraping) with the {@code Histórico Veicular}
 * risk pipeline (leilão / sinistro) and applies an automatic depreciation.
 *
 * <p><b>Depreciation engine:</b> when the consolidated risk band is
 * {@code BAIXO} the market price is kept intact ({@code precoAjustadoRisco ==
 * precoMedioOriginal}, {@code percentualRedutorAplicado == 0}). When the
 * vehicle shows any leilão or sinistro evidence ({@code MEDIO} or
 * {@code ALTO}), a configurable reducer of 20%–30% is subtracted from the
 * base price — {@code MEDIO} takes the floor of the band, {@code ALTO} the
 * ceiling, because a vehicle flagged for <i>both</i> leilão and sinistro
 * carries strictly more devaluation risk than one flagged for a single
 * indicator.</p>
 *
 * @param placa                  normalized placa (uppercase, no hyphen);
 * @param dadosVeiculo           vehicle identification from the Placa domain;
 * @param referenciaFipe         FIPE reference quote — {@code null} when the
 *                               codigoFipe could not be resolved;
 * @param mercado                marketplace price snapshot;
 * @param scoreAvaliacao         textual verdict comparing market vs. FIPE;
 * @param riscoConsolidado       risk band derived from the historico pipeline;
 * @param alertaRiscoGrave       {@code true} when the vehicle has any leilão
 *                               or sinistro evidence ({@code riscoConsolidado
 *                               != BAIXO}) — the single boolean a buyer-side
 *                               workflow keys on to block a purchase;
 * @param apontamentosHistorico  human-readable risk findings extracted from
 *                               the historico sources (empty when nada consta);
 * @param fontesHistorico        identifiers of the historico sources that
 *                               produced usable data;
 * @param precoMedioOriginal     market average price BEFORE the risk reducer;
 * @param percentualRedutorAplicado  fraction subtracted from the base price
 *                               (0.00 / 0.20 / 0.30);
 * @param precoAjustadoRisco     final price AFTER the automatic depreciation.
 */
@Schema(name = "AvaliacaoCompletaResponse",
        description = "Avaliação cruzada com o histórico de risco: preço de mercado depreciado automaticamente em 20%–30% quando há indício de leilão ou sinistro.")
public record AvaliacaoCompletaResponse(
        @Schema(description = "Placa normalizada (uppercase, sem hífen).", example = "ABC1D23")
        String placa,

        @Schema(description = "Dados de identificação do veículo retornados pelo módulo Placa.")
        PlacaResponse dadosVeiculo,

        @Schema(description = "Cotação FIPE de referência. **null** quando o código FIPE não foi resolvido.",
                nullable = true)
        FipePrecoResponse referenciaFipe,

        @Schema(description = "Snapshot de preços reais coletados via raspagem dos marketplaces.")
        DadosMercado mercado,

        @Schema(description = "Veredito comparando o preço médio de mercado contra a FIPE.",
                example = "Em linha com a FIPE")
        String scoreAvaliacao,

        @Schema(description = "Avaliação Técnica KBB — bandas Lojista vs. Particular, multiplicador por conservação. "
                + "Sempre presente: quando indisponível, vem com `disponivel=false` e mensagem explicativa.")
        PrecoKbbDTO avaliacaoKbb,

        @Schema(description = "Banda de risco consolidada do histórico veicular (BAIXO / MEDIO / ALTO).")
        RiscoConsolidado riscoConsolidado,

        @Schema(description = "true quando o veículo tem qualquer indício de leilão ou sinistro (riscoConsolidado != BAIXO).",
                example = "true")
        boolean alertaRiscoGrave,

        @Schema(description = "Lista de apontamentos do histórico — evidências de leilão/sinistro extraídas das fontes.",
                example = "[\"Leilão Copart 12/03/2024 - Sinistro/Salvado\", \"Sinistro Perda Total 02/2024 - Seguradora XYZ\"]")
        List<String> apontamentosHistorico,

        @Schema(description = "Fontes do histórico que produziram dados utilizáveis.",
                example = "[\"OlhoNoCarro\", \"LeilaoFree\"]")
        List<String> fontesHistorico,

        @Schema(description = "Preço médio de mercado ANTES do redutor de risco.", example = "47350.00")
        BigDecimal precoMedioOriginal,

        @Schema(description = "Fração subtraída do preço base pelo motor de depreciação (0.00 = sem redutor, 0.20 = risco médio, 0.30 = risco alto).",
                example = "0.30")
        BigDecimal percentualRedutorAplicado,

        @Schema(description = "Preço final APÓS a depreciação automática por risco.", example = "33145.00")
        BigDecimal precoAjustadoRisco
) {
}
