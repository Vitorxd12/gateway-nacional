package br.com.cernebr.gateway_nacional.financeiro.cvm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Detalhe completo de um fundo CVM. Espelha os ~40 campos do CSV
 * {@code cad_fi.csv} após o {@code header transform} feito pela BrasilAPI
 * (mantemos os mesmos nomes em snake_case para paridade direta).
 *
 * <p>A maioria dos campos é {@link String} porque o CSV CVM mistura
 * formatos (datas em {@code yyyy-MM-dd}, valores monetários como string com
 * separador decimal vírgula ou ponto, sinalizadores S/N) — manter como string
 * preserva exatamente o que o CVM publica e libera o consumidor de dependência
 * da nossa interpretação.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Detalhe completo de um fundo registrado na CVM")
public record FundoDetailResponse(
        @Schema(example = "00000000000000") String cnpj,
        @JsonProperty("denominacao_social")
        @Schema(name = "denominacao_social") String denominacaoSocial,
        @JsonProperty("tipo_fundo")
        @Schema(name = "tipo_fundo") String tipoFundo,
        @JsonProperty("codigo_cvm")
        @Schema(name = "codigo_cvm") String codigoCvm,
        String situacao,
        @JsonProperty("data_registro") String dataRegistro,
        @JsonProperty("data_constituicao") String dataConstituicao,
        @JsonProperty("data_cancelamento") String dataCancelamento,
        @JsonProperty("data_inicio_situacao") String dataInicioSituacao,
        @JsonProperty("data_inicio_atividade") String dataInicioAtividade,
        @JsonProperty("data_inicio_exercicio") String dataInicioExercicio,
        @JsonProperty("data_fim_exercicio") String dataFimExercicio,
        String classe,
        @JsonProperty("data_inicio_classe") String dataInicioClasse,
        String rentabilidade,
        String condominio,
        String cotas,
        @JsonProperty("fundo_exclusivo") String fundoExclusivo,
        @JsonProperty("tributacao_longo_prazo") String tributacaoLongoPrazo,
        @JsonProperty("publico_alvo") String publicoAlvo,
        @JsonProperty("entidade_investimento") String entidadeInvestimento,
        @JsonProperty("taxa_performance") String taxaPerformance,
        @JsonProperty("informacao_taxa_performance") String informacaoTaxaPerformance,
        @JsonProperty("taxa_administracao") String taxaAdministracao,
        @JsonProperty("informacao_taxa_administracao") String informacaoTaxaAdministracao,
        @JsonProperty("valor_patrimonio_liquido") String valorPatrimonioLiquido,
        @JsonProperty("data_patrimonio_liquido") String dataPatrimonioLiquido,
        String diretor,
        @JsonProperty("cnpj_administrador") String cnpjAdministrador,
        String administrador,
        @JsonProperty("tipo_pessoa_gestor") String tipoPessoaGestor,
        @JsonProperty("cpf_cnpj_gestor") String cpfCnpjGestor,
        String gestor,
        @JsonProperty("cnpj_auditor") String cnpjAuditor,
        String auditor,
        @JsonProperty("cnpj_custodiante") String cnpjCustodiante,
        String custodiante,
        @JsonProperty("cnpj_controlador") String cnpjControlador,
        String controlador,
        @JsonProperty("investimento_externo") String investimentoExterno,
        @JsonProperty("classe_anbima") String classeAnbima
) {
}
