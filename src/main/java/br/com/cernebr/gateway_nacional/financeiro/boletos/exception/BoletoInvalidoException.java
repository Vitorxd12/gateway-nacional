package br.com.cernebr.gateway_nacional.financeiro.boletos.exception;

/**
 * Sinaliza que a linha digitável submetida violou as regras determinísticas
 * da FEBRABAN — comprimento fora de 47/48 dígitos, ou DAC (módulo 10 / 11)
 * incorreto em qualquer um dos campos.
 *
 * <p>Distinta de {@link IllegalArgumentException} de propósito: o handler
 * global mapeia esta exceção em {@code HTTP 400 Bad Request} com
 * {@code ProblemDetail} dedicado, enquanto IAE genérica cairia no catch-all
 * {@code 500}. O motivo é semântico — falha de DAC é problema do input do
 * cliente, não do servidor.</p>
 */
public class BoletoInvalidoException extends RuntimeException {

    public BoletoInvalidoException(String message) {
        super(message);
    }
}
