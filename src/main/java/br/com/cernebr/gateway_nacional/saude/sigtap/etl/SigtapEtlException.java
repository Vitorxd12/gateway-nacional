package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import java.io.Serial;

/**
 * Sinaliza falha do pipeline ETL do SIGTAP.
 *
 * <p>Diferente das exceções de domínio (que viram 404/503 ao cliente),
 * esta NÃO atravessa o limite HTTP — é capturada pelo orquestrador, marca
 * o dataset em STAGING como {@code FAILED} e dispara o backoff
 * exponencial do scheduler. O ETL roda fora do request thread.</p>
 */
public class SigtapEtlException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SigtapEtlException(String message) {
        super(message);
    }

    public SigtapEtlException(String message, Throwable cause) {
        super(message, cause);
    }
}
