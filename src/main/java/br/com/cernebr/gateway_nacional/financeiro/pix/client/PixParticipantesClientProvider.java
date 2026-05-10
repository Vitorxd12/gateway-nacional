package br.com.cernebr.gateway_nacional.financeiro.pix.client;

import br.com.cernebr.gateway_nacional.financeiro.pix.dto.PixParticipantesResponse;

/**
 * Contrato dos providers de listagem de participantes do PIX.
 *
 * <p>{@code fetchAll()} é a única operação — não há lookup pontual, a fonte
 * canônica do BCB só publica a lista completa. Implementações são envolvidas
 * por Resilience4j (Circuit Breaker / TimeLimiter) e devolvem o envelope
 * unificado {@link PixParticipantesResponse}.</p>
 */
public interface PixParticipantesClientProvider {

    /**
     * Retorna a lista completa de participantes do PIX.
     *
     * @return envelope com {@code total}, {@code dataReferencia}, {@code fonte},
     *         {@code participantes[]}
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         quando o provider está inacessível, retorna corpo vazio,
     *         ou o Circuit Breaker está OPEN.
     */
    PixParticipantesResponse fetchAll();

    String providerName();
}
