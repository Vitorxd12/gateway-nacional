package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "SigtapCidResponse",
        description = "CID-10 referenciado pelo SIGTAP como diagnóstico que autoriza um procedimento."
)
public record SigtapCidResponse(
        @Schema(description = "Código CID-10", example = "I10") String codigo,
        @Schema(description = "Descrição oficial CID-10", example = "Hipertensão essencial (primária)") String nome,
        @Schema(description = "Se o CID é obrigatório para o procedimento (true) ou apenas autorizado (false)",
                example = "true")
        Boolean obrigatorio
) {
}
