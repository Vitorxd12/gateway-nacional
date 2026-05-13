package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(
        name = "SigtapProcedimentoResponse",
        description = "Detalhes canônicos de um procedimento SIGTAP (DataSUS) na competência ativa, incluindo valores de repasse SA/SH/SP e taxonomia hierárquica."
)
public record SigtapProcedimentoResponse(
        @Schema(description = "Código SIGTAP de 10 dígitos", example = "0201010042")
        String codigo,

        @Schema(description = "Nome oficial do procedimento", example = "BIOPSIA DE BOCA")
        String nome,

        @Schema(description = "Nível de complexidade do procedimento",
                example = "MEDIA",
                allowableValues = {"ATENCAO_BASICA", "MEDIA", "ALTA", "NAO_SE_APLICA"})
        String complexidade,

        @Schema(description = "Restrição de sexo do paciente, quando aplicável", example = "AMBOS")
        String sexo,

        @Schema(description = "Idade mínima do paciente, em dias", example = "0")
        Integer idadeMinimaDias,

        @Schema(description = "Idade máxima do paciente, em dias", example = "36500")
        Integer idadeMaximaDias,

        @Schema(description = "Quantidade máxima por execução", example = "1")
        Integer quantidadeMaxima,

        @Schema(description = "Tipo de financiamento SUS", example = "FAEC")
        String tipoFinanciamento,

        @Schema(description = "Valor de Serviços Ambulatoriais (BRL)", example = "12.43")
        BigDecimal valorSa,

        @Schema(description = "Valor de Serviços Hospitalares (BRL)", example = "0.00")
        BigDecimal valorSh,

        @Schema(description = "Valor de Serviços Profissionais (BRL)", example = "4.17")
        BigDecimal valorSp,

        @Schema(description = "Valor total = SA + SH + SP", example = "16.60")
        BigDecimal valorTotal,

        @Schema(description = "Código do grupo (2 dígitos)", example = "02")
        String grupoCodigo,

        @Schema(description = "Código do subgrupo (4 dígitos)", example = "0201")
        String subgrupoCodigo,

        @Schema(description = "Código da forma de organização (6 dígitos)", example = "020101")
        String formaOrganizacaoCodigo,

        @Schema(description = "Competência (AAAAMM) em que a tabela está vigente", example = "202605")
        String competencia
) {
}
