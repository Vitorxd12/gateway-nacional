package br.com.cernebr.gateway_nacional.veicular.tco.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Linha canônica do mapa de alíquotas estaduais — uma entrada por UF no
 * snapshot {@code data/ipva_aliquotas_uf.json}, carregado em memória no
 * startup pelo {@code AliquotaIpvaRepository}.
 *
 * <p>{@code aliquotaIpva} é a alíquota oficial de IPVA para veículos de
 * passeio (ex.: {@code 0.04} para SP/RJ/MG). {@code taxaTransferencia} é a
 * estimativa média da Taxa de Transferência de Propriedade cobrada pelo
 * Detran local — ambos {@link BigDecimal} para que o motor de cálculo
 * financeiro não sofra drift de precisão de {@code double}.</p>
 */
@Schema(name = "AliquotaUfEntry", hidden = true)
public record AliquotaUfEntry(
        String uf,
        BigDecimal aliquotaIpva,
        BigDecimal taxaTransferencia
) {
}
