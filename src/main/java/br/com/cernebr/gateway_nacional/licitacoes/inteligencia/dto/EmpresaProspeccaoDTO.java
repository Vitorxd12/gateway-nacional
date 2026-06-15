package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Linha de lead B2B consumida pelo CRM: uma empresa e seu resumo de participação
 * no recorte filtrado (quantas licitações, valor total homologado, última data).
 * Já enriquecida com CNAE/ramo e localização.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "EmpresaProspeccao", description = "Lead B2B: empresa + resumo de participação no recorte filtrado.")
public record EmpresaProspeccaoDTO(
        @Schema(description = "CNPJ (14 dígitos).", example = "02566043000164")
        String cnpj,
        @Schema(description = "Razão social.", example = "AARO COMERCIO, DISTRIBUICAO E SERVICOS LTDA")
        String razaoSocial,
        @Schema(description = "Nome fantasia, quando disponível.")
        String nomeFantasia,
        @Schema(description = "CNAE principal (subclasse 7 dígitos) — ramo de atuação.", example = "4773300")
        String cnaePrincipal,
        @Schema(description = "Porte (ME/EPP/DEMAIS).", example = "EMPRESA DE PEQUENO PORTE")
        String porte,
        @Schema(description = "UF da empresa.", example = "SE")
        String uf,
        @Schema(description = "Código IBGE do município da empresa.", example = "2800308")
        String municipioIbge,
        @Schema(description = "Qtde de participações (itens homologados) no recorte.", example = "3")
        int qtdParticipacoes,
        @Schema(description = "Soma do valor homologado no recorte (BRL).", example = "84200.00")
        BigDecimal valorTotalHomologado,
        @Schema(description = "Data da participação mais recente no recorte (UTC).", example = "2026-06-03T00:00:00Z")
        OffsetDateTime ultimaParticipacao
) {
}
