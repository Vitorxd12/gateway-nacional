package br.com.cernebr.gateway_nacional.saude.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One APS team (equipe) as reported by the e-Gestor APS detailed report,
 * normalized to the gateway's contract.
 *
 * <p>The upstream payload spreads the same conceptual fields across multiple
 * aliases ({@code coEquipe}, {@code coEquipeEsb}, {@code nuIne} for INE;
 * {@code tpEquipe}, {@code coTipoEquipe} for type). The Anti-Corruption Layer
 * picks the first non-blank match for each, so future renames upstream do
 * not break consumers.</p>
 *
 * <p>{@code statusSuspensao} is best-effort: when the upstream exposes an
 * explicit motive (e.g., {@code dsMotivoSuspensao}) it is propagated verbatim;
 * when no motive is found the value defaults to {@code "NÃO SUSPENSO"} —
 * which encodes the assumption that absence of suspension fields means the
 * team is being paid normally for that competency.</p>
 */
@Schema(name = "EquipeEGestorResponse",
        description = "Equipe APS reportada pelo e-Gestor, em formato unificado pelo Gateway Nacional.")
public record EquipeEGestorResponse(
        @Schema(description = "INE da equipe (10 dígitos canônicos quando o upstream entrega numérico)",
                example = "0000123456")
        String ine,

        @Schema(description = "Tipo da equipe (ESF, ESB, eMulti, ACS, ...)", example = "ESF")
        String tipoEquipe,

        @Schema(description = "Valor de custeio agregado da equipe na competência", example = "12345.67")
        BigDecimal valorCusteio,

        @Schema(description = "Status de suspensão. `NÃO SUSPENSO` quando o e-Gestor não reporta motivo; texto do motivo quando suspensa.",
                example = "NÃO SUSPENSO")
        String statusSuspensao
) {
}
