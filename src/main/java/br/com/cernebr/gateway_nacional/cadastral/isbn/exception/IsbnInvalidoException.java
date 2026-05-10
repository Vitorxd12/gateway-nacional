package br.com.cernebr.gateway_nacional.cadastral.isbn.exception;

/**
 * Sinaliza que o ISBN submetido violou as regras determinísticas — comprimento
 * fora de 10/13 dígitos após normalização ou checksum inválido.
 *
 * <p>Distinta de {@link IllegalArgumentException} de propósito: o handler
 * global mapeia esta exceção em {@code HTTP 400 Bad Request} com
 * {@code ProblemDetail} dedicado, enquanto IAE genérica cairia no catch-all
 * {@code 500}. Mesma motivação semântica do {@code BoletoInvalidoException}:
 * falha de checksum é problema do input do cliente, não do servidor.</p>
 */
public class IsbnInvalidoException extends RuntimeException {

    public IsbnInvalidoException(String message) {
        super(message);
    }
}
