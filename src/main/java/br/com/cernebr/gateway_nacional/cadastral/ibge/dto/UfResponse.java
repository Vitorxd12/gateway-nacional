package br.com.cernebr.gateway_nacional.cadastral.ibge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unidade Federativa do Brasil. Shape unificado a partir do
 * {@code servicodados.ibge.gov.br/api/v1/localidades/estados} com a capital
 * acrescentada via mapa estático (o IBGE não publica capital no payload de UF).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Unidade Federativa do Brasil")
public record UfResponse(
        @Schema(example = "35") Integer id,
        @Schema(example = "SP") String sigla,
        @Schema(example = "São Paulo") String nome,
        @JsonProperty("regiao_sigla")
        @Schema(name = "regiao_sigla", example = "SE") String regiaoSigla,
        @JsonProperty("regiao_nome")
        @Schema(name = "regiao_nome", example = "Sudeste") String regiaoNome,
        @Schema(example = "São Paulo") String capital
) {
}
