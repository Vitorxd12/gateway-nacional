package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Telefone",
        description = "Telefone do estabelecimento normalizado em DDD + número.")
public record TelefoneDTO(
        @Schema(description = "DDD (2 dígitos)", example = "61")
        String ddd,

        @Schema(description = "Número (8 ou 9 dígitos sem máscara)", example = "34939002")
        String numero
) {
}
