package br.com.cernebr.gateway_nacional.cadastral.isbn.client;

import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;

/**
 * Contrato para qualquer provedor upstream de ISBN. Implementações são
 * envolvidas por Resilience4j (Circuit Breaker / TimeLimiter) e convertem
 * o payload específico do provider em {@link IsbnResponse} unificado.
 */
public interface IsbnClientProvider {

    /**
     * Resolve os dados bibliográficos do ISBN.
     *
     * @param isbn ISBN normalizado (sem hífens, uppercase, 10 ou 13 dígitos
     *             — validado no controller)
     * @return payload unificado
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         quando o provider está inacessível, retorna corpo vazio,
     *         responde "ISBN não encontrado", ou o Circuit Breaker está OPEN.
     */
    IsbnResponse fetch(String isbn);

    String providerName();
}
