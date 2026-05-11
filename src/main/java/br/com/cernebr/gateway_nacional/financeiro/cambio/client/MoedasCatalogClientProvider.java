package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.MoedaResponse;

import java.util.List;

/**
 * Contrato comum entre os provedores do catálogo de moedas PTAX usado pelo
 * endpoint público {@code GET /api/v1/financeiro/cambio/moedas}.
 *
 * <p>Existe além do {@link BcbMoedasCatalogService} (que devolve apenas
 * {@code Set<String>} para validar pares no caminho crítico do
 * {@code CambioService}) porque a listagem pública precisa do payload
 * enriquecido — símbolo + nome + tipo — e queremos hedge entre BCB OLINDA
 * e BrasilAPI nesse caminho específico sem contaminar o catálogo-validador.</p>
 */
public interface MoedasCatalogClientProvider {

    /**
     * Devolve o catálogo enriquecido de moedas PTAX. Implementações lançam
     * {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
     * quando o upstream está indisponível ou o Circuit Breaker abriu.
     */
    List<MoedaResponse> listAll();

    String providerName();
}
