package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Página de resultados de prospecção. Contrato estável e barato em rede para o
 * CRM paginar leads.
 */
@Schema(name = "ProspeccaoPage", description = "Página de leads de prospecção.")
public record ProspeccaoPage(
        List<EmpresaProspeccaoDTO> conteudo,
        int pagina,
        int tamanho,
        long total,
        int totalPaginas
) {
    public static ProspeccaoPage of(List<EmpresaProspeccaoDTO> conteudo, int pagina, int tamanho, long total) {
        int totalPaginas = tamanho > 0 ? (int) Math.ceil((double) total / tamanho) : 0;
        return new ProspeccaoPage(conteudo, pagina, tamanho, total, totalPaginas);
    }
}
