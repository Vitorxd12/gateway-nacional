package br.com.cernebr.gateway_nacional.financeiro.boletos.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Distinção dos dois layouts FEBRABAN suportados pelo parser.
 *
 * <ul>
 *   <li>{@link #BANCARIO} — boleto bancário tradicional. Linha digitável de
 *       47 dígitos / código de barras de 44. Identificado pelo banco emissor
 *       nas posições 1-3 do código de barras.</li>
 *   <li>{@link #ARRECADACAO} — guia de arrecadação / concessionária (água,
 *       luz, tributos). Linha de 48 dígitos / barcode de 44 que sempre começa
 *       com o identificador de produto {@code 8}.</li>
 * </ul>
 */
@Schema(name = "TipoBoleto", description = "Layout FEBRABAN identificado pelo parser.")
public enum TipoBoleto {
    BANCARIO,
    ARRECADACAO
}
