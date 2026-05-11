package br.com.cernebr.gateway_nacional.licitacoes.client;

import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoDetalheDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoResumoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Portal;

import java.util.List;
import java.util.Optional;

/**
 * Contrato comum entre os quatro clients de licitação.
 *
 * <p>Cada implementação fala um dialeto diferente (REST JSON oficial, scraping
 * HTML, JSON ajax interno…). A normalização para os DTOs canônicos é
 * responsabilidade do client — o service apenas orquestra.</p>
 *
 * <p>Métodos lançam {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
 * quando o portal está fora ou o Circuit Breaker está aberto. Detalhe não
 * encontrado devolve {@link Optional#empty()} para que o controller distinga
 * 404 de 503 — listagem nunca devolve null (lista vazia significa "portal
 * respondeu, mas sem licitações no filtro pedido").</p>
 */
public interface LicitacaoClient {

    /** Portal que esse client cobre — usado como tag de métrica e roteamento. */
    Portal portal();

    /** Nome humano para logs e métricas (provider tag). */
    String providerName();

    /**
     * Lista licitações ativas filtradas. Os filtros são best-effort: se o
     * portal não suporta um filtro nativo, o client aplica em memória antes
     * de devolver. {@code uf} e {@code modalidade} podem ser null.
     */
    List<LicitacaoResumoDTO> listarAtivas(String uf, String modalidade);

    /** Detalhe completo. {@link Optional#empty()} = portal respondeu 404. */
    Optional<LicitacaoDetalheDTO> buscarDetalhe(String identificador);
}
