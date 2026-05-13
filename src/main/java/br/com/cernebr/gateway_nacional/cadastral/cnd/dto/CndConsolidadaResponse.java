package br.com.cernebr.gateway_nacional.cadastral.cnd.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "CndConsolidadaResponse",
        description = "Visão consolidada das três certidões negativas de débito necessárias para habilitação em licitações e contratações públicas: Trabalhista (TST), FGTS (Caixa) e Federal (PGFN/Receita)."
)
public record CndConsolidadaResponse(
        @Schema(description = "CNPJ consultado (somente dígitos)", example = "00000000000191")
        String cnpj,

        @Schema(description = "Certidão Negativa de Débitos Trabalhistas — TST")
        CndTrabalhista trabalhista,

        @Schema(description = "Certificado de Regularidade do FGTS (CRF) — Caixa Econômica Federal")
        CndFgts fgts,

        @Schema(description = "Certidão Conjunta Negativa de Débitos Federais — PGFN/Receita Federal")
        CndFederal federal,

        @Schema(description = "Quantidade de certidões resolvidas com sucesso (0–3)", example = "3")
        int certidoesResolvidas,

        @Schema(description = "Se true, ao menos um provedor degradou — cliente deve reinspecionar o sub-bloco com status=INDISPONIVEL", example = "false")
        boolean degradado
) {
}
