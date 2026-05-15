package br.com.cernebr.gateway_nacional.cadastral.cnpj.client;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;

/**
 * Contrato dos providers que alimentam o {@code CnpjConsolidadoDTO}.
 *
 * <p>Diferente do antigo {@link CnpjClientProvider} (legado, devolve o DTO
 * raso {@code CnpjResponse}), cada implementação aqui devolve um
 * {@link CnpjConsolidadoDTO} parcial — apenas com os campos que o upstream
 * conseguiu materializar. O merger no serviço consolida os parciais por
 * prioridade e identifica o provider sobrevivente.</p>
 *
 * <p>Convenção: implementações <em>devem</em> popular
 * {@link CnpjConsolidadoDTO#fontesSobreviventes()} com a única entrada
 * {@link #providerName()} para que o merger possa rastrear a procedência
 * campo-a-campo via metadados de log + métricas.</p>
 */
public interface CnpjConsolidadoClientProvider {

    /**
     * Resolve o CNPJ contra o provider e devolve um parcial canônico.
     *
     * @param cnpj 14 dígitos numéricos sem máscara
     * @return parcial canônico, nunca {@code null}
     * @throws br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException
     *         quando o provider falha, devolve payload inválido ou está sob CB OPEN.
     */
    CnpjConsolidadoDTO fetch(String cnpj);

    /** Identificador estável usado em logs, métricas e na lista de fontes sobreviventes. */
    String providerName();
}
