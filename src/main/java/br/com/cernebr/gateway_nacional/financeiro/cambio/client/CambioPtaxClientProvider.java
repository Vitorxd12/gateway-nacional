package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioResponse;

import java.util.List;

/**
 * Contrato dos providers de cotação PTAX (oficial Banco Central).
 *
 * <p>Distinto do {@link CambioClient} (AwesomeAPI/spot comercial) por dois motivos
 * semânticos:</p>
 * <ol>
 *   <li><b>Cobertura de pares:</b> PTAX só responde para pares
 *       {@code XXX-BRL} onde {@code XXX} consta no catálogo de moedas
 *       publicadas pelo BCB (USD, EUR, GBP, JPY, CHF, etc.). Cripto
 *       (BTC, ETH) e cross-currency sem BRL (USD-EUR) não têm equivalente
 *       PTAX e devem cair no fallback {@link CambioClient}.</li>
 *   <li><b>Janela temporal:</b> PTAX é fixing diário, não tempo real.
 *       Se o pedido chegar antes da publicação do dia, o provider retrocede
 *       até o último dia útil bancário com PTAX disponível — comportamento
 *       transparente ao chamador.</li>
 * </ol>
 *
 * <p>Falha (incluindo "pair not supported by PTAX") leva o {@code CambioService}
 * a cascatear para o AwesomeAPI sequencialmente.</p>
 */
public interface CambioPtaxClientProvider {

    /**
     * Resolve a PTAX oficial para todos os pares informados.
     *
     * @param pares string canonicalizada já em UPPERCASE (ex: {@code "USD-BRL,EUR-BRL"})
     * @return lista com uma {@link CambioResponse} por par, na mesma ordem
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         quando qualquer par não é PTAX-elegível, quando o provider
     *         está inacessível, ou quando o Circuit Breaker está OPEN.
     *         O {@code CambioService} trata QUALQUER falha aqui como gatilho
     *         de cascata para o fallback AwesomeAPI.
     */
    List<CambioResponse> fetchPtax(String pares);

    String providerName();
}
