package br.com.cernebr.gateway_nacional.saude.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Estabelecimento de saúde cadastrado no CNES (Cadastro Nacional de
 * Estabelecimentos de Saúde) num município.
 *
 * <p>Modelado a partir das colunas que o CerneBR realmente consome dos
 * relatórios CNES: identificador, nome, tipo de unidade segundo a
 * tabela DATASUS, e um flag pré-derivado de pertencimento à Atenção Primária
 * — "atenção básica" no jargão da regulação SUS. O flag fica cravado no
 * payload (em vez de ser derivado pelo consumidor) porque a classificação
 * de tipo de unidade tem regras particulares por tipo (UBS vs USF vs UPA
 * vs CNES de centros de atenção secundária) que valem ser centralizadas
 * num único ponto.</p>
 */
@Schema(name = "EstabelecimentoCnesResponse",
        description = "Estabelecimento de saúde do CNES com classificação pré-derivada de pertencimento à APS.")
public record EstabelecimentoCnesResponse(
        @Schema(description = "Código CNES (7 dígitos) do estabelecimento", example = "2469776")
        String cnes,

        @Schema(description = "Nome oficial registrado no CNES", example = "UBS JARDIM SANTA INES")
        String nome,

        @Schema(description = "Tipo de unidade (taxonomia DATASUS)", example = "UBS - Unidade Básica de Saúde")
        String tipoUnidade,

        @Schema(description = "Verdadeiro quando o estabelecimento integra a Atenção Básica (UBS, USF, UAPS, etc.)",
                example = "true")
        boolean atencaoBasica
) {
}
