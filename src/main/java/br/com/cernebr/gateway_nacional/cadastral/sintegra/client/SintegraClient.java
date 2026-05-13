package br.com.cernebr.gateway_nacional.cadastral.sintegra.client;

import br.com.cernebr.gateway_nacional.cadastral.sintegra.dto.SintegraResponse;

import java.util.Optional;

/**
 * Contrato comum para provedores de Sintegra / Inscrição Estadual.
 *
 * <p>A UF é opcional no contrato: quando informada, o provedor pode pular
 * direto à SEFAZ correspondente (em provedores capazes); quando ausente,
 * cabe ao provedor varrer o cadastro centralizado (CCC) e identificar todas
 * as UFs com inscrição vigente para o CNPJ. O contract retorna {@link Optional}
 * para diferenciar "não localizado" (404) de "indisponível" (503).</p>
 */
public interface SintegraClient {

    Optional<SintegraResponse> fetch(String cnpj, String uf);

    String providerName();
}
