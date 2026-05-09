package br.com.cernebr.gateway_nacional.juridico.processos.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Metadados de um processo judicial obtidos via API Pública DataJud (CNJ),
 * em formato unificado pelo Gateway Nacional.
 *
 * <p>O DataJud é um índice Elasticsearch sobre todos os tribunais do país;
 * o numeroProcesso CNJ (20 dígitos) embute o tribunal de origem nas
 * posições 14-15 — o {@code TribunalResolver} usa essa informação para
 * dirigir a consulta ao alias correto.</p>
 */
@Schema(name = "ProcessoResponse",
        description = "Metadados de um processo judicial via DataJud/CNJ.")
public record ProcessoResponse(
        @Schema(description = "Numeração única CNJ (20 dígitos puros).", example = "00008323520184013202")
        String numeroProcesso,

        @Schema(description = "Sigla do tribunal de origem.", example = "TJSP")
        String tribunal,

        @Schema(description = "Classe processual (descrição).", example = "Procedimento Comum Cível")
        String classeProcessual,

        @Schema(description = "Assunto principal.", example = "Indenização por Dano Moral")
        String assunto,

        @Schema(description = "Data de ajuizamento do feito.", example = "2024-08-13", format = "date")
        LocalDate dataAjuizamento,

        @Schema(description = "Situação atual (último movimento ou grau atual).", example = "Em grau de recurso")
        String situacaoAtual
) {
}
