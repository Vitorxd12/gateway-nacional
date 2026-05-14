package br.com.cernebr.gateway_nacional.veicular.tco.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * "Custo Total de Entrada" (TCO de entrada) de um veículo — cruza o domínio
 * Veicular (cotação FIPE) com o domínio Fiscal/Cadastral estadual (alíquota
 * de IPVA + taxa de transferência do Detran da UF informada).
 *
 * <p>Todos os campos monetários são {@link BigDecimal} com escala 2 e
 * {@code RoundingMode.HALF_UP} — cotações financeiras não toleram o drift
 * de precisão de {@code double} em simulações de compra/crédito.</p>
 *
 * <p>Decomposição do cálculo:</p>
 * <pre>
 *   estimativaIpvaAnual  = valorFipe × aliquotaIpvaAplicada
 *   custoTotalEntrada    = estimativaIpvaAnual + taxaTransferenciaEstimada
 * </pre>
 *
 * <p>{@code fallbackAplicado} sinaliza que a UF consultada não estava
 * mapeada na base canônica e o sistema aplicou a alíquota modal nacional
 * de 3% — o consumidor da API deve tratar o resultado como aproximação.</p>
 */
@Schema(
        name = "TcoEntradaVeicularDTO",
        description = "Custo Total de Entrada de um veículo: cotação FIPE + IPVA estimado + taxa de transferência estadual."
)
public record TcoEntradaVeicularDTO(

        @Schema(description = "Código FIPE consultado no padrão 000000-0", example = "005340-0")
        String codigoFipe,

        @Schema(description = "Marca do veículo", example = "Volkswagen")
        String marca,

        @Schema(description = "Modelo (versão completa)", example = "Cross UP! TSI 1.0 12V Total Flex")
        String modelo,

        @Schema(description = "Ano modelo da cotação utilizada (32000 indica Zero KM)", example = "2018")
        int anoModelo,

        @Schema(description = "Mês de referência da cotação FIPE consumida", example = "março de 2024")
        String mesReferenciaFipe,

        @Schema(description = "Valor exato da Tabela FIPE em reais", example = "100000.00")
        BigDecimal valorFipe,

        @Schema(description = "UF base utilizada para o cálculo fiscal", example = "SP")
        String ufBase,

        @Schema(description = "Alíquota de IPVA aplicada para a UF (fração decimal — 0.04 = 4%)", example = "0.04")
        BigDecimal aliquotaIpvaAplicada,

        @Schema(description = "Estimativa de IPVA anual = valorFipe × alíquota", example = "4000.00")
        BigDecimal estimativaIpvaAnual,

        @Schema(description = "Estimativa média da Taxa de Transferência de Propriedade do Detran da UF", example = "231.89")
        BigDecimal taxaTransferenciaEstimada,

        @Schema(description = "Custo Total de Entrada = estimativaIpvaAnual + taxaTransferenciaEstimada", example = "4231.89")
        BigDecimal custoTotalEntrada,

        @Schema(
                description = "true quando a UF não estava na base canônica e foi aplicada a alíquota modal nacional (3%) como fallback resiliente",
                example = "false"
        )
        boolean fallbackAplicado
) {
}
