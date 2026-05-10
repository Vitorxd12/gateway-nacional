package br.com.cernebr.gateway_nacional.financeiro.cvm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Versão sumarizada de um fundo, devolvida na listagem paginada
 * {@code GET /cvm/fundos}. Mantém só os 5 campos mais usados na navegação;
 * o detalhe completo vem em {@link FundoDetailResponse}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Sumário de fundo CVM (resposta da listagem paginada)")
public record FundoSummaryResponse(
        @Schema(example = "00000000000000") String cnpj,
        @JsonProperty("denominacao_social")
        @Schema(name = "denominacao_social", example = "FUNDO XYZ FII") String denominacaoSocial,
        @JsonProperty("codigo_cvm")
        @Schema(name = "codigo_cvm", example = "0123456") String codigoCvm,
        @JsonProperty("tipo_fundo")
        @Schema(name = "tipo_fundo", example = "FII") String tipoFundo,
        @Schema(example = "EM FUNCIONAMENTO NORMAL") String situacao
) {
}
