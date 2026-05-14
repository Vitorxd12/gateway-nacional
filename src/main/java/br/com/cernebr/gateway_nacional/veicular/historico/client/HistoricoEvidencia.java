package br.com.cernebr.gateway_nacional.veicular.historico.client;

/**
 * Raw output of a single historico scraper — one fragment that the
 * orchestrator merges into the consolidated DTO.
 *
 * <p>Scrapers MUST NOT throw to signal "no evidence found": throwing means
 * the source failed (it gets dropped from {@code fontesConsultadas}).
 * Returning an evidence with both booleans {@code false} means "the source
 * answered, but no risk markers were detected" — which is itself signal
 * (the placa is listed as clean in that source).</p>
 *
 * @param fonte           stable provider id (e.g., {@code "LeilaoFree"});
 * @param indicioLeilao   true when this source detected leilão markers;
 * @param indicioSinistro true when this source detected sinistro markers;
 * @param detalhe         human-readable excerpt from the page; null when the
 *                        source did not produce a string fragment.
 */
public record HistoricoEvidencia(
        String fonte,
        boolean indicioLeilao,
        boolean indicioSinistro,
        String detalhe
) {

    public static HistoricoEvidencia clean(String fonte) {
        return new HistoricoEvidencia(fonte, false, false, null);
    }
}
