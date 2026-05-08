package br.com.cernebr.gateway_nacional.cadastral.cnae.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified CNAE (Classificação Nacional de Atividades Econômicas) entry
 * exposed by the Gateway, regardless of which upstream provider (IBGE,
 * tabela local) actually resolved the lookup.
 *
 * <p>The CNAE has two granularities in Brazilian fiscal practice:
 * <ul>
 *   <li><b>Classe</b> — 5 digits, the level the IBGE publishes;</li>
 *   <li><b>Subclasse</b> — 7 digits, the level the Receita Federal uses
 *       in CNPJ records (and which {@code /api/v1/cnpj/...} echoes back).</li>
 * </ul>
 *
 * <p>This DTO carries the subclasse code (7 digits) — the granularity
 * consumers actually need to render alongside CNPJ data. Internally we
 * resolve the code through IBGE's {@code /api/v2/cnae/subclasses}
 * endpoint, falling back to a bake-in JSON snapshot when IBGE is down.</p>
 */
@Schema(name = "CnaeResponse",
        description = "Atividade econômica CNAE (subclasse, 7 dígitos) unificada pelo Gateway Nacional.")
public record CnaeResponse(
        @Schema(description = "Código CNAE subclasse (7 dígitos, sem separadores)", example = "6422100")
        String codigo,

        @Schema(description = "Descrição oficial da subclasse", example = "BANCOS MÚLTIPLOS, COM CARTEIRA COMERCIAL")
        String descricao
) {
}
