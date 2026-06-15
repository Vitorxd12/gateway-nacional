package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

/**
 * Helpers de normalização de CNPJ compartilhados pela ingestão e pelo worker
 * de enriquecimento. Escopo PJ: só consideramos documentos de 14 dígitos
 * (CPF de fornecedor pessoa física fica fora do caso de uso B2B).
 */
public final class Cnpjs {

    private Cnpjs() {
    }

    /** Devolve o CNPJ só com dígitos (14) ou {@code null} se não for PJ. */
    public static String normalizar(String raw) {
        if (raw == null) {
            return null;
        }
        String digitos = raw.replaceAll("\\D", "");
        return digitos.length() == 14 ? digitos : null;
    }
}
