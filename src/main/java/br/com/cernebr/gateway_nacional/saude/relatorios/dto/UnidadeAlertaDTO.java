package br.com.cernebr.gateway_nacional.saude.relatorios.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Posto de saúde com status de risco derivado pelo orquestrador
 * Termômetro APS — destinado ao gestor municipal como lista priorizada
 * de busca ativa.
 *
 * <p>Apenas estabelecimentos APS (UBS, USF, UAPS, etc.) entram nesta
 * lista — UPAs, hospitais e laboratórios ficam de fora porque não
 * compõem o cálculo do PAB-Variável.</p>
 *
 * <p>{@code statusRisco}:
 * <ul>
 *   <li>{@link StatusRisco#URGENTE} — nota geral abaixo de 6.0 OU meta
 *       não alcançada com nota abaixo de 6.0; busca ativa imediata
 *       recomendada;</li>
 *   <li>{@link StatusRisco#ATENCAO} — meta não alcançada porém nota
 *       acima de 6.0; intervenção pontual nas equipes da unidade;</li>
 *   <li>{@link StatusRisco#OK} — meta alcançada; manter cadência atual.</li>
 * </ul>
 */
@Schema(name = "UnidadeAlertaDTO",
        description = "Estabelecimento APS com status de risco derivado a partir da nota Previne Brasil do município.")
public record UnidadeAlertaDTO(
        @Schema(description = "Código CNES (7 dígitos) do estabelecimento", example = "2469776")
        String cnes,

        @Schema(description = "Nome registrado no CNES", example = "UBS Jardim Santa Inês")
        String nome,

        @Schema(description = "Tipo de unidade (taxonomia DATASUS)", example = "UBS - Unidade Básica de Saúde")
        String tipoUnidade,

        @Schema(description = "Status de risco derivado")
        StatusRisco statusRisco,

        @Schema(description = "Indicadores específicos abaixo da meta no quadrimestre — alimenta a observação ao gestor.",
                example = "[\"Pré-natal — 6 ou mais consultas\",\"Saúde da criança — vacinação\"]")
        List<String> indicadoresDefasados,

        @Schema(description = "Orientação textual para o ERP/gestor sobre a próxima ação.",
                example = "Disparar busca ativa imediata: 2 indicadores defasados (Pré-natal — 6 ou mais consultas, Saúde da criança — vacinação).")
        String observacao
) {

    @Schema(name = "UnidadeAlertaDTO.StatusRisco",
            description = "Severidade derivada da nota Previne Brasil do município.")
    public enum StatusRisco {
        OK, ATENCAO, URGENTE
    }
}
