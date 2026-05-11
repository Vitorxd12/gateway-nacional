package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    /**
     * Resolve a PTAX para uma moeda específica em uma data específica — sem
     * retry retroativo. Diferente de {@link #fetchPtax(String)}, que sempre
     * retorna a cotação mais recente (D-1 com retrocesso automático), este
     * método honra a data passada e devolve {@link Optional#empty()} se o BCB
     * não publicou cotação naquela data (fim de semana, feriado bancário, ou
     * data anterior à existência da PTAX para essa moeda).
     *
     * <p><b>Semântica das saídas:</b></p>
     * <ul>
     *   <li>{@code Optional<CambioResponse>} preenchido — boletim resolvido
     *       (preferindo "Fechamento PTAX"); o {@code CambioService} responde 200;</li>
     *   <li>{@code Optional.empty()} — upstream confirmou que NÃO há
     *       publicação para a data; o {@code CambioService} segue para o
     *       próximo provider e, se todos retornarem empty, responde 404
     *       determinístico via {@code ResourceNotFoundException};</li>
     *   <li>{@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException} —
     *       falha de rede / Circuit Breaker aberto. O {@code CambioService}
     *       cascateia para o próximo provider; se todos falharem, devolve 503.</li>
     * </ul>
     *
     * <p>O par destino é sempre {@code BRL} (PTAX só publica vs Real); o
     * caller passa apenas a moeda de origem.</p>
     *
     * @param moeda código ISO em UPPERCASE (ex.: {@code "USD"})
     * @param data  data de referência (deve ser passada — futuro não é suportado)
     */
    Optional<CambioResponse> fetchPtaxByDate(String moeda, LocalDate data);

    String providerName();
}
