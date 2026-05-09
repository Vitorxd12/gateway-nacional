package br.com.cernebr.gateway_nacional.saude.ans.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Operadora de plano de saúde registrada na ANS, em formato unificado pelo
 * Gateway Nacional. Origem: dump diário PDA da ANS (relatórios de operadoras
 * ativas + canceladas), consolidado em snapshot in-memory.
 *
 * <p>{@code situacao} é {@code "ATIVA"} ou {@code "CANCELADA"} — a string
 * literal exposta na API; o ERP não precisa olhar duas rotas para descobrir
 * o status do convênio.</p>
 */
@Schema(name = "OperadoraAnsResponse",
        description = "Operadora de plano de saúde registrada na ANS, ativa ou cancelada.")
public record OperadoraAnsResponse(
        @Schema(description = "Registro ANS — 6 dígitos numéricos canônicos.", example = "326305")
        String registroAns,

        @Schema(description = "CNPJ da operadora (apenas dígitos, 14 caracteres).", example = "29309127000179")
        String cnpj,

        @Schema(description = "Razão social registrada na ANS.", example = "AMIL ASSISTÊNCIA MÉDICA INTERNACIONAL S.A.")
        String razaoSocial,

        @Schema(description = "Modalidade ANS (Medicina de Grupo, Cooperativa, Seguradora etc.).", example = "Medicina de Grupo")
        String modalidade,

        @Schema(description = "Situação cadastral — ATIVA ou CANCELADA.", example = "ATIVA",
                allowableValues = {"ATIVA", "CANCELADA"})
        String situacao
) {
}
