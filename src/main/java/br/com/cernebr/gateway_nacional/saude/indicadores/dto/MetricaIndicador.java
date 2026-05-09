package br.com.cernebr.gateway_nacional.saude.indicadores.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Métrica unitária de um dos sete indicadores do Previne Brasil/PMA.
 *
 * <p>Os indicadores oficiais (numerados I-1 a I-7 nas portarias do
 * Ministério da Saúde) cobrem: pré-natal, sífilis/HIV em gestantes,
 * citopatológico, vacinação infantil, hipertensos com PA aferida,
 * diabéticos com HbA1c solicitada e crianças com puericultura. O
 * gateway expõe o nome textual ao invés do código numérico para que o
 * ERP consumidor não tenha que rastrear o glossário cada vez que uma
 * portaria renumera os indicadores.</p>
 *
 * <p>Percentuais são {@link BigDecimal} para que o ERP possa fazer
 * agregações/médias sem o ruído de {@code double} — o domínio de
 * desempenho da APS movimenta repasse PAB-Variável proporcional, e
 * arredondamento errado em produção já gerou auditoria do TCE.</p>
 */
@Schema(name = "MetricaIndicador",
        description = "Indicador unitário do Previne Brasil/PMA com o desempenho atual e a meta.")
public record MetricaIndicador(
        @Schema(description = "Nome textual do indicador (em vez do código numérico — o ERP não precisa decorar I-1..I-7).",
                example = "Proporção de gestantes com pelo menos 6 consultas pré-natal")
        String nomeIndicador,

        @Schema(description = "Percentual atualmente alcançado pelo município no quadrimestre.", example = "78.40")
        BigDecimal percentualAtual,

        @Schema(description = "Percentual da meta pactuada com o Ministério da Saúde para o indicador.", example = "60.00")
        BigDecimal percentualMeta
) {
}
