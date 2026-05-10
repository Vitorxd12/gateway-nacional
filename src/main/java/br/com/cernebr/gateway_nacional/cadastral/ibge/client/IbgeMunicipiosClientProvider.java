package br.com.cernebr.gateway_nacional.cadastral.ibge.client;

import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;

import java.util.List;

/**
 * Contrato dos providers de listagem de municípios por UF. Implementações
 * são envolvidas por Resilience4j e o {@code IbgeService} as orquestra via
 * {@code HedgedExecutor} — vence o primeiro com sucesso.
 *
 * <p>Não inclui o provider {@code wikipedia} da BrasilAPI por política
 * PESADO: scraping HTML é frágil e custoso. Mantemos só os dois providers
 * REST simples que conseguem competir em latência.</p>
 */
public interface IbgeMunicipiosClientProvider {

    /**
     * Lista todos os municípios da UF informada.
     *
     * @param siglaUf sigla canonicalizada (uppercase, 2 letras), validada no controller
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         quando o provider está inacessível ou retorna lista vazia.
     */
    List<MunicipioResponse> fetchByUf(String siglaUf);

    String providerName();
}
