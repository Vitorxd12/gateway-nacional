package br.com.cernebr.gateway_nacional.saude.tuss.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Código TUSS (Terminologia Unificada da Saúde Suplementar) — publicado pela
 * ANS e usado em faturamento de planos de saúde, autorização de procedimentos
 * e troca de dados HL7-XML entre operadoras e prestadores.
 */
@Schema(name = "TussCodigoResponse",
        description = "Entrada do dicionário TUSS — código numérico + descrição oficial ANS.")
public record TussCodigoResponse(
        @Schema(description = "Código TUSS canônico (8 dígitos numéricos sem separadores).",
                example = "10101012")
        String tuss,

        @Schema(description = "Descrição oficial do procedimento conforme dicionário ANS.",
                example = "Consulta em consultório (no horário normal ou preestabelecido)")
        String nome
) {
}
