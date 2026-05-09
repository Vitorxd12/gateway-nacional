package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified CNPJ payload exposed by the Gateway, regardless of which upstream
 * provider (BrasilAPI, ReceitaWS, MinhaReceita) actually resolved the lookup.
 * Designed for B2B onboarding and NF-e issuance — carries the minimum fields
 * required by Brazilian tax compliance.
 */
@Schema(name = "CnpjResponse", description = "Dados cadastrais essenciais de uma pessoa jurídica brasileira, em formato unificado pelo Gateway Nacional.")
public record CnpjResponse(
        @Schema(description = "CNPJ consultado (somente dígitos)", example = "00000000000191")
        String cnpj,

        @Schema(description = "Razão social oficial registrada na Receita Federal", example = "BANCO DO BRASIL SA")
        String razaoSocial,

        @Schema(description = "Nome fantasia / nome comercial", example = "BB")
        String nomeFantasia,

        @Schema(description = "Código CNAE da atividade econômica principal (somente dígitos)", example = "6422100")
        String cnaePrincipal,

        @Schema(description = "Situação cadastral atual", example = "ATIVA")
        String status,

        @Schema(description = "CEP da sede", example = "70073900")
        String cep,

        @Schema(description = "Sigla da Unidade Federativa da sede", example = "DF")
        String uf,

        @Schema(description = "Município da sede", example = "Brasília")
        String municipio
) {
}
