package br.com.cernebr.gateway_nacional.saude.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One healthcare professional registered in the CNES (Cadastro Nacional de
 * Estabelecimentos de Saúde) for a given establishment + team, normalized
 * to the gateway's contract.
 *
 * <p>The upstream payload (DATASUS internal API) uses several aliases for
 * the same conceptual fields ({@code noProfissional}/{@code nomeProfissional}
 * for name, {@code nuCns}/{@code cns}/{@code nuCnsProfissional} for CNS,
 * {@code dtEntrada}/{@code dataEntrada} for entry date). The Anti-Corruption
 * Layer picks the first non-blank match, so future renames upstream do
 * not break consumers.</p>
 *
 * <p>{@code cargaHoraria} aggregates ambulatorial + other hours
 * ({@code chAmb + chOutros}) — same contract used in the AutoAPSFinancias
 * pipeline for completeness checks. {@code dataEntrada} is propagated
 * verbatim ({@code dd/MM/yyyy} or ISO depending on upstream); empty string
 * when the upstream omits the field.</p>
 */
@Schema(name = "ProfissionalCnesResponse",
        description = "Profissional vinculado a uma equipe num estabelecimento CNES.")
public record ProfissionalCnesResponse(
        @Schema(description = "Código CNES (7 dígitos) do estabelecimento", example = "2469776")
        String cnesDaUnidade,

        @Schema(description = "INE (10 dígitos) da equipe à qual o profissional está vinculado",
                example = "0000123456")
        String ineEquipe,

        @Schema(description = "Nome do profissional", example = "MARIA DA SILVA")
        String nome,

        @Schema(description = "CNS — Cartão Nacional de Saúde do profissional", example = "700000000000000")
        String cns,

        @Schema(description = "Código CBO (Classificação Brasileira de Ocupações)", example = "225125")
        String cbo,

        @Schema(description = "Carga horária semanal total (chAmb + chOutros)", example = "40")
        int cargaHoraria,

        @Schema(description = "Data de entrada do profissional na equipe (formato como entregue pelo upstream — usualmente dd/MM/yyyy ou ISO)",
                example = "01/03/2023")
        String dataEntrada
) {
}
