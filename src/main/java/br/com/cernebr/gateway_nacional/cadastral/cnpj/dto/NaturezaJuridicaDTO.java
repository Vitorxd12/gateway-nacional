package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NaturezaJuridica",
        description = "Natureza jurídica conforme tabela CONCLA (código IBGE 4 dígitos / DV-1).")
public record NaturezaJuridicaDTO(
        @Schema(description = "Código da natureza jurídica (formato 'NNNN-D')",
                example = "203-4")
        String codigo,

        @Schema(description = "Descrição oficial da natureza jurídica",
                example = "Sociedade Anônima Aberta")
        String descricao
) {

    /** Código RFB de Empresário Individual — QSA estruturalmente vazio. */
    public static final String EMPRESARIO_INDIVIDUAL = "213-5";

    public boolean isEmpresarioIndividual() {
        return codigo != null && EMPRESARIO_INDIVIDUAL.equals(codigo.trim());
    }
}
