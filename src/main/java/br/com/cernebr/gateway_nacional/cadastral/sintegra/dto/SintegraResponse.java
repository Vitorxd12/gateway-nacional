package br.com.cernebr.gateway_nacional.cadastral.sintegra.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "SintegraResponse",
        description = "Inscrição estadual de uma pessoa jurídica e seu enquadramento cadastral em uma Unidade Federativa, unificado a partir do Cadastro Centralizado de Contribuintes (CCC/SVRS) e agregadores."
)
public record SintegraResponse(
        @Schema(description = "CNPJ consultado (somente dígitos)", example = "07526557000100")
        String cnpj,

        @Schema(description = "Inscrição Estadual", example = "0732030210114")
        String ie,

        @Schema(description = "Sigla da Unidade Federativa", example = "RS")
        String uf,

        @Schema(description = "Situação cadastral perante a SEFAZ estadual", example = "ATIVA",
                allowableValues = {"ATIVA", "SUSPENSA", "BAIXADA", "INAPTA", "NULA"})
        String situacaoCadastral,

        @Schema(description = "Data da situação cadastral em ISO-8601", example = "2019-04-15")
        String dataSituacao,

        @Schema(description = "Regime de apuração do ICMS", example = "NORMAL",
                allowableValues = {"NORMAL", "SIMPLES_NACIONAL", "MEI", "ESTIMATIVA", "SUBSTITUTO"})
        String regimeApuracao,

        @Schema(description = "Nome do provedor vencedor do hedge — auditável para SLA", example = "CCC-SVRS")
        String fonte
) {
}
