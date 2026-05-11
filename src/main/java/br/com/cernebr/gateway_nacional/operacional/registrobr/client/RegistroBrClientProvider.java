package br.com.cernebr.gateway_nacional.operacional.registrobr.client;

import br.com.cernebr.gateway_nacional.operacional.registrobr.dto.RegistroBrResponse;

/**
 * Contrato comum dos provedores de disponibilidade de domínio .br
 * (Registro.br direto e proxy BrasilAPI).
 *
 * <p>Implementações lançam
 * {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
 * quando o upstream falha ou o Circuit Breaker abre.</p>
 */
public interface RegistroBrClientProvider {

    RegistroBrResponse consultar(String dominio);

    String providerName();
}
