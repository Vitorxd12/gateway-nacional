package br.com.cernebr.gateway_nacional.juridico.processos.client;

import org.springframework.stereotype.Component;

/**
 * Resolve o alias do índice DataJud a partir do numeroProcesso CNJ.
 *
 * <p>O padrão CNJ (Resolução 65/2008) tem o formato
 * {@code NNNNNNN-DD.AAAA.J.TR.OOOO} = 20 dígitos puros sem máscara. O par
 * {@code J.TR} (posições 13-15 zero-indexadas) identifica unicamente o
 * tribunal de origem:</p>
 *
 * <pre>
 *   J=4 (Justiça Federal)     TR=01..06  → trf1..trf6
 *   J=5 (Justiça Trabalho)    TR=01..24  → trt1..trt24
 *   J=8 (Justiça Estadual)    TR=01..27  → tjac, tjal, ...
 *   J=1 STF, J=3 STJ, J=6 Eleitoral, J=7 Militar — tratados explicitamente.
 * </pre>
 *
 * <p>Cobre os segmentos representativos do tráfego (Estadual e Federal); os
 * raros (Eleitoral, Militar Estadual) caem em {@link UnsupportedOperationException}
 * de forma explícita — preferível a roteamento errado silencioso.</p>
 */
@Component
public class TribunalResolver {

    /** Mapeia o código TR da Justiça Estadual para a sigla UF (ordem oficial CNJ). */
    private static final String[] TJ_BY_TR = {
            null,  "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO",
            "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ",
            "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"
    };

    public Resolved resolve(String numeroProcesso) {
        if (numeroProcesso == null || numeroProcesso.length() != 20) {
            throw new IllegalArgumentException(
                    "numeroProcesso deve ter 20 dígitos; recebido: " + (numeroProcesso == null ? "null" : numeroProcesso.length()));
        }
        char j = numeroProcesso.charAt(13);
        int tr = Integer.parseInt(numeroProcesso.substring(14, 16));

        return switch (j) {
            case '1' -> new Resolved("STF",  "api_publica_stf");
            case '3' -> new Resolved("STJ",  "api_publica_stj");
            case '4' -> new Resolved("TRF" + tr, "api_publica_trf" + tr);
            case '5' -> new Resolved("TRT" + tr, "api_publica_trt" + tr);
            case '8' -> {
                if (tr < 1 || tr >= TJ_BY_TR.length || TJ_BY_TR[tr] == null) {
                    throw new IllegalArgumentException("TR estadual inválido: " + tr);
                }
                String uf = TJ_BY_TR[tr];
                yield new Resolved("TJ" + uf, "api_publica_tj" + uf.toLowerCase());
            }
            // J=6 (Eleitoral) e J=7 (Militar União/Estadual) não são cobertos
            // pelo DataJud público com esse padrão de alias — assumir o nome
            // errado pediria 404 silencioso, então é melhor falhar cedo.
            default -> throw new UnsupportedOperationException(
                    "Segmento de Justiça J=" + j + " não suportado pelo gateway. Suportados: 1 (STF), 3 (STJ), 4 (TRFs), 5 (TRTs), 8 (TJs).");
        };
    }

    /**
     * Resultado da resolução — sigla do tribunal (apresentação) e alias do
     * índice (consumo no path da API DataJud).
     */
    public record Resolved(String sigla, String alias) {
    }
}
