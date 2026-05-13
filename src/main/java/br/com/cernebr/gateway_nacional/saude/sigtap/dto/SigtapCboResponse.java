package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "SigtapCboResponse",
        description = "CBO no recorte SIGTAP — apenas as ocupações habilitadas pelo SUS a faturar BPA-I."
)
public record SigtapCboResponse(
        @Schema(description = "Código CBO (6 dígitos)", example = "225125") String codigo,
        @Schema(description = "Nome canônico da ocupação", example = "MÉDICO CLÍNICO") String nome
) {
}
