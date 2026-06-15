package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Licitação da qual uma empresa participou (lado empresa→licitações). Uma linha
 * por edital, agregando os itens em que a empresa teve resultado.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "LicitacaoParticipada", description = "Edital em que a empresa participou, com resumo da participação.")
public record LicitacaoParticipadaDTO(
        String portal,
        String identificador,
        String numero,
        String objeto,
        String setor,
        String modalidade,
        String orgaoUf,
        String orgaoMunicipioIbge,
        String orgaoMunicipioNome,
        String papel,
        int qtdItens,
        BigDecimal valorTotalHomologado,
        OffsetDateTime dataResultado
) {
}
