package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.saude.dto.ProducaoSisabResponse;

import java.util.List;

/**
 * Contract for any SISAB (Sistema de Informação em Saúde para a Atenção
 * Básica) integration. SISAB is a JSF/PrimeFaces application — the
 * gateway scrapes the rendered HTML table after a best-effort form
 * submission with ViewState handling. When the upstream rejects the
 * simulated request (anti-bot, token expiration), the CB trips and a
 * 503 is surfaced.
 */
public interface SisabClientProvider {

    /**
     * Resolves the validation status for every team that submitted production
     * in the given municipality and competency.
     */
    List<ProducaoSisabResponse> fetchProducao(String ibge6, int ano, int mes);

    String providerName();
}
