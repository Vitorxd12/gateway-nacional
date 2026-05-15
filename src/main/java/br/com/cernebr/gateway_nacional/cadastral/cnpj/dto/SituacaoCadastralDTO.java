package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Bloco de Situação Cadastral conforme schema canônico da RFB.
 *
 * <p>O código segue a tabela oficial (01-NULA, 02-ATIVA, 03-SUSPENSA,
 * 04-INAPTA, 08-BAIXADA). O texto humano em {@code descricao} é mantido
 * em paralelo porque alguns providers entregam apenas a descrição.</p>
 */
@Schema(name = "SituacaoCadastral",
        description = "Bloco de situação cadastral RFB com código, descrição, data e motivo.")
public record SituacaoCadastralDTO(
        @Schema(description = "Código da situação cadastral conforme RFB",
                example = "02")
        String codigo,

        @Schema(description = "Descrição textual da situação", example = "ATIVA")
        String descricao,

        @Schema(description = "Data em que a situação atual foi registrada",
                example = "1966-08-01")
        LocalDate data,

        @Schema(description = "Motivo da situação (preenchido para BAIXADA/SUSPENSA/INAPTA)",
                example = "SEM MOTIVO")
        String motivo
) {
}
