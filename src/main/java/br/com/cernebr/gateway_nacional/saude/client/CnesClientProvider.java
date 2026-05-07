package br.com.cernebr.gateway_nacional.saude.client;

import br.com.cernebr.gateway_nacional.saude.dto.ProfissionalCnesResponse;

import java.util.List;

/**
 * Contract for any CNES (Cadastro Nacional de Estabelecimentos de Saúde)
 * integration. Implementations are wrapped by Resilience4j Circuit Breakers;
 * a portal change or anti-bot block trips the CB and the orchestrator
 * surfaces a clean 503 instead of a stack trace.
 *
 * <p>The CNES upstream indexes establishments by the composite key
 * {@code {ibge}{cnes}} — that's why this contract requires the IBGE
 * alongside the CNES. The 7-digit CNES is not unique on its own across
 * the country.</p>
 */
public interface CnesClientProvider {

    /**
     * Resolves all professionals registered in the given establishment.
     * Returning an empty list is treated as a failure by the orchestrator;
     * throw a
     * {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
     * for unequivocal failures so metrics and CB count it correctly.
     */
    List<ProfissionalCnesResponse> fetchProfissionais(String cnesBase, String ibge6);

    String providerName();
}
