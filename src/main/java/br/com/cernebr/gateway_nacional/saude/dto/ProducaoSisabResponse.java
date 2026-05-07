package br.com.cernebr.gateway_nacional.saude.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the SISAB validation report — for a given (ibge, cnes, ine)
 * tuple in a competency, did the team's production import succeed?
 *
 * <p>The SISAB validation portal is a JSF/PrimeFaces application; the
 * gateway scrapes the rendered HTML table after a best-effort JSF form
 * submission. Each row of that table maps to one of these records.
 * {@code statusValidacao} holds the value verbatim from the {@code Validacao}
 * column ({@code "Aprovado"} or {@code "Reprovado"}) — case preserved as
 * shown in the portal.</p>
 */
@Schema(name = "ProducaoSisabResponse",
        description = "Linha de validação de produção do SISAB unificada pelo Gateway Nacional.")
public record ProducaoSisabResponse(
        @Schema(description = "Código IBGE do município (6 dígitos canônicos SUS)", example = "292870")
        String ibge,

        @Schema(description = "Código CNES (7 dígitos) do estabelecimento que enviou a produção", example = "2469776")
        String cnes,

        @Schema(description = "INE (10 dígitos) da equipe", example = "0000123456")
        String ine,

        @Schema(description = "Status de validação da produção SISAB. Valores típicos: Aprovado, Reprovado.",
                example = "Aprovado")
        String statusValidacao
) {
}
