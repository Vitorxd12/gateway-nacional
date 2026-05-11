package br.com.cernebr.gateway_nacional.operacional.cptec.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Previsão de tempo para uma cidade, agregando 1–6 dias de horizonte.
 *
 * <p>Os limites do upstream estão documentados em
 * {@code servicos.cptec.inpe.br/XML/#previsao-tempo}: o CPTEC publica em
 * média 6 dias; pedidos acima desse teto são silenciosamente truncados.</p>
 */
@Schema(name = "PrevisaoClimaResponse",
        description = "Previsão multi-dias para uma cidade brasileira via CPTEC/INPE.")
public record PrevisaoClimaResponse(
        @Schema(description = "Nome da cidade", example = "São Paulo")
        String cidade,

        @Schema(description = "Sigla da UF", example = "SP")
        String estado,

        @Schema(description = "Data/hora da última atualização do boletim", example = "10/05/2026 13:00")
        String atualizadoEm,

        @Schema(description = "Vetor com a previsão dia-a-dia (até 6 dias)")
        List<DiaPrevisao> clima
) {

    @Schema(name = "DiaPrevisao", description = "Previsão diária com mínima, máxima e condição.")
    public record DiaPrevisao(
            @Schema(description = "Data ISO yyyy-MM-dd", example = "2026-05-11")
            String data,

            @Schema(description = "Condição (sigla CPTEC)", example = "pn")
            String condicao,

            @Schema(description = "Condição traduzida", example = "Parcialmente Nublado")
            String condicaoDesc,

            @Schema(description = "Temperatura mínima em °C", example = "14")
            Integer min,

            @Schema(description = "Temperatura máxima em °C", example = "22")
            Integer max,

            @Schema(description = "Índice ultravioleta", example = "8")
            Number indiceUv
    ) {
    }
}
