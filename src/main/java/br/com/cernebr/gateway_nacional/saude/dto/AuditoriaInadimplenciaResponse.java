package br.com.cernebr.gateway_nacional.saude.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One audit verdict per suspended APS team — the cross-domain answer to the
 * question: <i>"my município lost a federal repasse this competency. Which
 * team, in which establishment, did not validate production, and who works
 * in that team?"</i>
 *
 * <p>Composed by {@code AuditoriaSaudeService} from three independent sources
 * that each cover one slice of the problem:
 * <ul>
 *   <li>e-Gestor → suspension status and motive (<i>why</i> the team lost it);</li>
 *   <li>CNES → professionals bound to the team (<i>who</i> works there);</li>
 *   <li>SISAB → production validation (<i>did the team send approved data</i>).</li>
 * </ul>
 *
 * <p>{@code profissionaisInadimplentes} is populated only when SISAB lacks an
 * "Aprovado" entry for the team's INE — i.e., the team did not validate
 * production in the competency, which is the financial cause of the
 * suspension. When the team is suspended for a non-production reason, the
 * list arrives empty and {@code motivoSuspensao} carries the literal text
 * from the e-Gestor.</p>
 */
@Schema(name = "AuditoriaInadimplenciaResponse",
        description = "Veredito cruzado e-Gestor + CNES + SISAB para uma equipe APS suspensa.")
public record AuditoriaInadimplenciaResponse(
        @Schema(description = "INE (10 dígitos) da equipe auditada", example = "0000123456")
        String ineEquipe,

        @Schema(description = "Status do repasse para a equipe na competência. Valores típicos: SUSPENSO, NÃO SUSPENSO.",
                example = "SUSPENSO")
        String statusRepasse,

        @Schema(description = "Motivo da suspensão verbatim do e-Gestor (vazio quando não há suspensão).",
                example = "EQUIPE SEM ENVIO DE PRODUCAO APROVADA NO SISAB")
        String motivoSuspensao,

        @Schema(description = "Código CNES (7 dígitos) da unidade auditada", example = "2469776")
        String cnesUnidade,

        @Schema(description = "Profissionais da equipe sem produção aprovada no SISAB. Lista vazia quando a equipe enviou produção aprovada (e a suspensão tem outra causa) ou quando a equipe não está suspensa por motivo de produção.")
        List<String> profissionaisInadimplentes
) {
}
