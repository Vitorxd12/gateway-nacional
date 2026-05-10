package br.com.cernebr.gateway_nacional.cadastral.ibge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Detalhe de uma UF, enriquecido com a estimativa populacional mais recente
 * publicada pelo IBGE no agregado {@code 6579} (variável {@code 9324}).
 *
 * <p>Quando o agregado de população não estiver acessível, os campos
 * {@code populacaoEstimada} e {@code periodo} ficam {@code null} — preserva
 * o caminho feliz mesmo se só a chamada de UF tiver sucesso.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "UF com estimativa populacional mais recente do IBGE")
public record UfDetailResponse(
        @Schema(example = "35") Integer id,
        @Schema(example = "SP") String sigla,
        @Schema(example = "São Paulo") String nome,
        @JsonProperty("regiao_sigla")
        @Schema(name = "regiao_sigla", example = "SE") String regiaoSigla,
        @JsonProperty("regiao_nome")
        @Schema(name = "regiao_nome", example = "Sudeste") String regiaoNome,
        @Schema(example = "São Paulo") String capital,
        @JsonProperty("populacao_estimada")
        @Schema(name = "populacao_estimada", example = "44411238") Long populacaoEstimada,
        @Schema(example = "2025", description = "Ano do dado populacional") String periodo
) {
}
