package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "EnderecoCompleto",
        description = "Endereço completo do estabelecimento conforme cadastro RFB.")
public record EnderecoCompletoDTO(
        @Schema(description = "Tipo + logradouro (ex.: 'RUA XV DE NOVEMBRO')",
                example = "QUADRA SAUN QUADRA 5 LOTE B TORRES I, II E III")
        String logradouro,

        @Schema(description = "Número do imóvel", example = "S/N")
        String numero,

        @Schema(description = "Complemento", example = "ANDAR 1 A 16 E SUBSOLO")
        String complemento,

        @Schema(description = "Bairro", example = "ASA NORTE")
        String bairro,

        @Schema(description = "CEP (somente dígitos)", example = "70040912")
        String cep,

        @Schema(description = "Município conforme tabela IBGE")
        MunicipioDTO municipio,

        @Schema(description = "Sigla da Unidade Federativa", example = "DF")
        String uf
) {
}
