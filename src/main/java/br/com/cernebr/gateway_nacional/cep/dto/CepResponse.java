package br.com.cernebr.gateway_nacional.cep.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified address payload exposed by the Gateway, regardless of which upstream
 * provider (ViaCEP, BrasilAPI, AwesomeAPI) actually resolved the CEP.
 */
@Schema(name = "CepResponse", description = "Endereço resolvido a partir do CEP, em formato unificado pelo Gateway Nacional.")
public record CepResponse(
        @Schema(description = "CEP consultado", example = "01001-000")
        String cep,

        @Schema(description = "Logradouro (rua, avenida, etc.)", example = "Praça da Sé")
        String logradouro,

        @Schema(description = "Complemento adicional do endereço", example = "lado ímpar")
        String complemento,

        @Schema(description = "Bairro", example = "Sé")
        String bairro,

        @Schema(description = "Município", example = "São Paulo")
        String localidade,

        @Schema(description = "Sigla da Unidade Federativa", example = "SP")
        String uf,

        @Schema(description = "Código IBGE do município", example = "3550308")
        String ibge
) {
}
