package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Municipio",
        description = "Município conforme tabela do IBGE.")
public record MunicipioDTO(
        @Schema(description = "Código IBGE do município (7 dígitos)", example = "5300108")
        String codigoIbge,

        @Schema(description = "Nome do município", example = "Brasília")
        String nome
) {
}
