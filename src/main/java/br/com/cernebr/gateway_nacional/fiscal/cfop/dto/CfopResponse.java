package br.com.cernebr.gateway_nacional.fiscal.cfop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * CFOP (Código Fiscal de Operações e Prestações) entry — single source
 * of truth for any line of NF-e or NFS-e issuance.
 *
 * <p>The CFOP is a 4-digit code defined by Convênio SINIEF that classifies
 * every fiscal operation in Brazil (purchase, sale, return, transfer,
 * inter/intra-state, foreign trade…). The first digit identifies the
 * direction and scope of the operation:
 * <ul>
 *   <li>{@code 1xxx/2xxx/3xxx} — entrance (intra-state / inter-state / foreign);</li>
 *   <li>{@code 5xxx/6xxx/7xxx} — exit  (intra-state / inter-state / foreign).</li>
 * </ul>
 *
 * <p>Unlike NCM/CNAE this table is essentially static — there has been no
 * meaningful change since the 2002 SINIEF update. We serve it from an
 * in-memory map (~600 entries, &lt;100 KB) loaded at startup; no upstream
 * call, no Redis cache, lookup in O(1).</p>
 */
@Schema(name = "CfopResponse",
        description = "Código Fiscal de Operações e Prestações (CFOP) servido in-memory pelo Gateway Nacional.")
public record CfopResponse(
        @Schema(description = "Código CFOP (4 dígitos)", example = "5102")
        String codigo,

        @Schema(description = "Descrição oficial da operação fiscal",
                example = "Venda de mercadoria adquirida ou recebida de terceiros")
        String descricao,

        @Schema(description = "Notas explicativas e regras de aplicação do CFOP (verbatim do Convênio SINIEF)",
                example = "Classificam-se neste código as vendas de mercadorias adquiridas ou recebidas de terceiros para industrialização ou comercialização...")
        String aplicacao
) {
}
