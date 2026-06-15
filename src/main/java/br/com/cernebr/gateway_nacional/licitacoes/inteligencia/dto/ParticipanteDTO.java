package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Empresa participante de uma licitação (lado licitação→empresas). Uma linha por
 * participação (uma empresa pode ganhar vários itens do mesmo edital).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Participante", description = "Empresa participante/vencedora de um item de uma licitação.")
public record ParticipanteDTO(
        String cnpj,
        String razaoSocial,
        String nomeFantasia,
        String cnaePrincipal,
        String porte,
        String uf,
        String municipioIbge,
        String papel,
        Integer itemSequencial,
        BigDecimal valorHomologado,
        OffsetDateTime dataResultado
) {
}
