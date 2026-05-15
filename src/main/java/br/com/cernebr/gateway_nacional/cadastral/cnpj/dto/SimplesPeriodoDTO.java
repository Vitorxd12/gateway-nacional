package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(name = "SimplesPeriodo",
        description = "Período de opção (entrada/saída) Simples Nacional ou MEI.")
public record SimplesPeriodoDTO(
        @Schema(description = "Regime (SIMPLES ou MEI)", example = "SIMPLES")
        String regime,

        @Schema(description = "Data de adesão ao regime", example = "2007-07-01")
        LocalDate dataOpcao,

        @Schema(description = "Data de exclusão (null se permanece optante)",
                example = "2018-01-01")
        LocalDate dataExclusao
) {
}
