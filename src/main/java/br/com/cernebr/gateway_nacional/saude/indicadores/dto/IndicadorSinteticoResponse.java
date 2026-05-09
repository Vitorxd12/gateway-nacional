package br.com.cernebr.gateway_nacional.saude.indicadores.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resposta do "Termômetro de Desempenho da APS" para um município em
 * um quadrimestre.
 *
 * <p>O Previne Brasil consolida cada quadrimestre em uma nota sintética
 * de 0 a 10 (média ponderada dos 7 indicadores). Acima da meta = repasse
 * integral do PAB-Variável; abaixo = repasse parcial. O campo
 * {@code metaAlcancada} é o veredicto binário que o ERP precisa para o
 * fluxo de previsão financeira.</p>
 */
@Schema(name = "IndicadorSinteticoResponse",
        description = "Termômetro de desempenho da APS de um município no quadrimestre, agregando os 7 indicadores Previne Brasil/PMA.")
public record IndicadorSinteticoResponse(
        @Schema(description = "Código IBGE do município (6 ou 7 dígitos).", example = "3550308")
        String codigoIbge,

        @Schema(description = "Quadrimestre de referência no formato AAAAQq (q ∈ {1,2,3}).", example = "2025Q3")
        String quadrimestre,

        @Schema(description = "Nota sintética consolidada (0 a 10) — média ponderada dos 7 indicadores.", example = "8.45")
        BigDecimal notaFinal,

        @Schema(description = "Indica se a meta consolidada do quadrimestre foi alcançada.", example = "true")
        Boolean metaAlcancada,

        @ArraySchema(arraySchema = @Schema(description = "Lista dos 7 indicadores Previne Brasil/PMA com o desempenho unitário."),
                schema = @Schema(implementation = MetricaIndicador.class))
        List<MetricaIndicador> metricas
) {
}
