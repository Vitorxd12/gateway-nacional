package br.com.cernebr.gateway_nacional.cadastral.simples.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "SimplesNacionalResponse",
        description = "Enquadramento da pessoa jurídica nos regimes Simples Nacional e SIMEI, consolidado a partir do portal Consulta Optantes da Receita Federal."
)
public record SimplesNacionalResponse(
        @Schema(description = "CNPJ consultado (somente dígitos)", example = "00000000000191")
        String cnpj,

        @Schema(description = "Indica se a empresa é optante pelo Simples Nacional na data da consulta", example = "true")
        boolean optanteSimples,

        @Schema(description = "Data da opção pelo Simples Nacional no formato ISO-8601, ou null quando não optante", example = "2018-01-01")
        String dataOpcaoSimples,

        @Schema(description = "Indica se a empresa é optante pelo Simei (microempreendedor individual)", example = "false")
        boolean optanteSimei,

        @Schema(description = "Data da opção pelo Simei no formato ISO-8601, ou null quando não optante", example = "null")
        String dataOpcaoSimei,

        @Schema(description = "Nome do provedor que efetivamente respondeu — auditável para BI e SLA", example = "ReceitaFederal-OptantesScraper")
        String fonte
) {
}
