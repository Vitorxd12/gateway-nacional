package br.com.cernebr.gateway_nacional.financeiro.bancos.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified bank payload exposed by the Gateway, regardless of which upstream
 * (BrasilAPI, in-memory BACEN dump) actually resolved the lookup.
 *
 * <p>The {@code codigo} (COMPE — Código de Compensação) is the 3-digit
 * zero-padded compensation code used in TED/DOC routing. Some institutions
 * registered at BACEN exist only for PIX (no COMPE) and may surface with
 * {@code codigo == null}.</p>
 */
@Schema(name = "BancoResponse", description = "Instituição financeira brasileira em formato unificado pelo Gateway Nacional.")
public record BancoResponse(
        @Schema(description = "Identificador do Sistema de Pagamentos Brasileiro (8 dígitos)", example = "00000000")
        String ispb,

        @Schema(description = "Nome curto / razão abreviada", example = "BCO DO BRASIL S.A.")
        String nome,

        @Schema(description = "Código de compensação (COMPE) com 3 dígitos zero-pad", example = "001")
        String codigo,

        @Schema(description = "Razão social completa", example = "Banco do Brasil S.A.")
        String nomeCompleto
) {
}
