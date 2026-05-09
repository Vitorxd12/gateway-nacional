package br.com.cernebr.gateway_nacional.financeiro.boletos.dto;

import br.com.cernebr.gateway_nacional.financeiro.boletos.enums.TipoBoleto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resultado do parse de uma linha digitável FEBRABAN.
 *
 * <p>Campos opcionais por design:
 * <ul>
 *   <li>{@code codigoBarras} — sempre derivável quando a linha é válida.</li>
 *   <li>{@code bancoEmissor} — somente em {@link TipoBoleto#BANCARIO} (a
 *       guia de arrecadação não carrega banco no layout, e sim segmento +
 *       identificador de empresa, que exige tabela externa para resolver
 *       em "banco emissor"). Em arrecadação fica {@code null}.</li>
 *   <li>{@code valor} — extraído das posições 9-18 do barcode (bancário) ou
 *       4-14 (arrecadação). Quando o boleto é "sem valor" (fator/valor zero,
 *       comum em emissões de cobrança a definir), retorna {@code BigDecimal.ZERO}.</li>
 *   <li>{@code dataVencimento} — calculada via Fator de Vencimento FEBRABAN
 *       (4 dígitos, base 07/10/1997 + rollover 22/02/2025) apenas para
 *       {@link TipoBoleto#BANCARIO}. Arrecadação não tem campo padrão de
 *       vencimento — o layout do campo livre varia por segmento e órgão
 *       emissor — então retorna {@code null}.</li>
 * </ul>
 */
@Schema(name = "BoletoResponse",
        description = "Dados extraídos e validados algoritmicamente de uma linha digitável FEBRABAN.")
public record BoletoResponse(
        @Schema(description = "Linha digitável normalizada (apenas dígitos, sem pontuação ou espaços).",
                example = "23793381286008223160995000063305989220000026035")
        String linhaDigitavel,

        @Schema(description = "Código de barras de 44 dígitos derivado da linha digitável.",
                example = "23799892200000260353381260082231609500006330")
        String codigoBarras,

        @Schema(description = "Layout FEBRABAN identificado.")
        TipoBoleto tipo,

        @Schema(description = "Código do banco emissor (3 dígitos COMPE). Nulo em guias de arrecadação.",
                example = "237", nullable = true)
        String bancoEmissor,

        @Schema(description = "Valor do título em reais. Pode ser BigDecimal.ZERO em boletos 'a definir'.",
                example = "260.35")
        BigDecimal valor,

        @Schema(description = "Data de vencimento. Nulo em arrecadação (layout não padronizado).",
                example = "2022-03-12", nullable = true)
        LocalDate dataVencimento,

        @Schema(description = "Resultado da validação dos dígitos verificadores (sempre true em respostas 2xx — falha gera 400 antes do response).",
                example = "true")
        boolean valido
) {
}
