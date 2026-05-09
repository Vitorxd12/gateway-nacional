package br.com.cernebr.gateway_nacional.financeiro.bancos.client;

import br.com.cernebr.gateway_nacional.financeiro.bancos.dto.BancoResponse;

import java.util.List;

/**
 * Contract for any bank-catalogue source — external HTTP provider or the
 * bundled in-memory BACEN dump. Two operations are supported because the
 * upstream APIs distinguish between "list all" and "fetch one" calls and
 * each has its own latency / availability profile.
 */
public interface BancoClientProvider {

    /**
     * Returns the complete list of registered institutions.
     *
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable or the Circuit Breaker is OPEN.
     */
    List<BancoResponse> fetchAll();

    /**
     * Returns a single institution by its 3-digit compensation code (COMPE).
     *
     * @param codigo zero-padded 3-digit code (e.g. {@code "001"}). Already
     *               normalized at the controller boundary.
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         when the provider is unreachable, the code is not registered
     *         with this particular provider, or the Circuit Breaker is OPEN.
     */
    BancoResponse fetchByCodigo(String codigo);

    /**
     * Stable provider identifier used for logging and observability tags.
     */
    String providerName();
}
