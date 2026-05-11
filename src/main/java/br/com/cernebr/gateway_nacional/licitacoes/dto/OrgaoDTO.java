package br.com.cernebr.gateway_nacional.licitacoes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Órgão promotor da licitação — UASG no caso federal (ComprasNet),
 * razão social no caso dos portais privados (BLL/BNC/Licitanet).
 *
 * <p>{@code uasg} é nullable porque só o ComprasNet expõe esse identificador
 * de forma estável; os portais privados trazem CNPJ ou um identificador
 * interno do portal — normalizamos para {@code identificadorPortal}.</p>
 */
@Schema(name = "OrgaoDTO",
        description = "Órgão promotor / unidade compradora.")
public record OrgaoDTO(
        @Schema(description = "Razão social ou nome publicado do órgão.", example = "MINISTÉRIO DA FAZENDA")
        String nome,

        @Schema(description = "Código UASG (federal, 6 dígitos). null para portais privados.", example = "170531")
        String uasg,

        @Schema(description = "CNPJ do órgão promotor, quando publicado.", example = "00394460000141")
        String cnpj,

        @Schema(description = "Identificador interno do portal (slug, hash). Usado para deep-link em /detalhe.", example = "PMSP-2026-001")
        String identificadorPortal,

        @Schema(description = "Município sede do órgão, quando aplicável.", example = "São Paulo")
        String municipio,

        @Schema(description = "UF do órgão (sigla 2 letras).", example = "SP")
        String uf
) {
}
