package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "InformacoesSimplesMei",
        description = "Enquadramento atual do CNPJ nos regimes Simples Nacional e MEI " +
                "(quando entregue pelo provider).")
public record InformacoesSimplesMeiDTO(
        @Schema(description = "Empresa atualmente optante pelo Simples Nacional.",
                example = "true")
        Boolean optanteSimples,

        @Schema(description = "Empresário atualmente optante pelo SIMEI (MEI).",
                example = "false")
        Boolean optanteMei,

        @Schema(description = "Lista de períodos de opção (entrada e eventual saída) por regime.")
        List<SimplesPeriodoDTO> listaPeriodos
) {

    public static InformacoesSimplesMeiDTO empty() {
        return new InformacoesSimplesMeiDTO(null, null, List.of());
    }
}
