package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Cnae",
        description = "Atividade econômica conforme tabela CONCLA/IBGE (CNAE 2.3).")
public record CnaeDTO(
        @Schema(description = "Código CNAE de 7 dígitos", example = "6422100")
        String codigo,

        @Schema(description = "Descrição oficial da subclasse CNAE",
                example = "Bancos múltiplos, com carteira comercial")
        String descricao
) {
}
