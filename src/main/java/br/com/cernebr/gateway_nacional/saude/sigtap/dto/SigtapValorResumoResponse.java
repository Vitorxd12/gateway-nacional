package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(
        name = "SigtapValorResumoResponse",
        description = "Linha de análise financeira: procedimento + valor total + breakdown SA/SH/SP. Otimizado para ranking por repasse."
)
public record SigtapValorResumoResponse(
        @Schema(description = "Código SIGTAP", example = "0301010072") String codigo,
        @Schema(description = "Nome do procedimento", example = "ATENDIMENTO MEDICO EM ATENCAO BASICA") String nome,
        @Schema(description = "Grupo (2 dígitos)", example = "03") String grupoCodigo,
        @Schema(description = "Valor SA (BRL)", example = "10.00") BigDecimal valorSa,
        @Schema(description = "Valor SH (BRL)", example = "0.00") BigDecimal valorSh,
        @Schema(description = "Valor SP (BRL)", example = "0.00") BigDecimal valorSp,
        @Schema(description = "Valor total (SA+SH+SP)", example = "10.00") BigDecimal valorTotal
) {
}
