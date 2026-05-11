package br.com.cernebr.gateway_nacional.operacional.cptec.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cidade do banco do CPTEC/INPE — usada como insumo para todos os demais
 * endpoints de previsão e ondas (que exigem {@code cityCode} numérico).
 */
@Schema(name = "CidadeCptecResponse",
        description = "Cidade no banco do CPTEC/INPE — ponto de entrada para previsão e ondas.")
public record CidadeCptecResponse(
        @Schema(description = "Nome oficial da cidade no CPTEC", example = "São Paulo")
        String nome,

        @Schema(description = "Unidade federativa", example = "SP")
        String estado,

        @Schema(description = "Região brasileira derivada da UF (Norte, Nordeste, Sudeste, Sul, Centro-Oeste)",
                example = "Sudeste")
        String regiao,

        @Schema(description = "Código numérico interno do CPTEC — required por /clima/previsao e /ondas",
                example = "244")
        Integer id
) {
}
