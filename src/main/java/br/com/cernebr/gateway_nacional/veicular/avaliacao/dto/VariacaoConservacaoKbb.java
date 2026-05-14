package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multiplicadores KBB por estado de conservação. O KBB publica três bandas
 * de preço, uma por nível: {@code EXCELENTE} é o teto da faixa (sem retoques,
 * histórico de revisões em concessionária), {@code BOM} é o ponto neutro
 * (uso normal, sem reparos pendentes), {@code REGULAR} é o piso (necessita
 * pintura, retoques visíveis, peças não-críticas trocadas).
 *
 * <p>Cada slot carrega o fator multiplicativo aplicado sobre o preço médio
 * do canal — por convenção, valores próximos a {@code 1.00} para EXCELENTE,
 * {@code 0.95} para BOM e {@code 0.85}–{@code 0.90} para REGULAR. Quando o
 * KBB não publica o split por conservação para o veículo consultado, os três
 * slots podem chegar {@code null} simultaneamente.</p>
 */
@Schema(name = "VariacaoConservacaoKbb",
        description = "Multiplicadores KBB aplicados por estado de conservação (EXCELENTE / BOM / REGULAR).")
public record VariacaoConservacaoKbb(
        @Schema(description = "Multiplicador para conservação EXCELENTE — teto da faixa, sem retoques.",
                example = "1.00")
        BigDecimal excelente,

        @Schema(description = "Multiplicador para conservação BOM — ponto neutro do canal.",
                example = "0.95")
        BigDecimal bom,

        @Schema(description = "Multiplicador para conservação REGULAR — piso da faixa, necessita reparos.",
                example = "0.87")
        BigDecimal regular
) {

    /**
     * Vista em Map para consumidores que preferem chaves enum-like ao invés
     * de campos posicionais (ex: serialização para tabela frontend). A ordem
     * de inserção é preservada via {@link LinkedHashMap}.
     */
    public Map<String, BigDecimal> asMap() {
        Map<String, BigDecimal> map = new LinkedHashMap<>(3);
        map.put("EXCELENTE", excelente);
        map.put("BOM", bom);
        map.put("REGULAR", regular);
        return map;
    }
}
