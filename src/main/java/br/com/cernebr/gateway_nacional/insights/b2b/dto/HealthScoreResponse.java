package br.com.cernebr.gateway_nacional.insights.b2b.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Aggregated B2B onboarding dossier — single CNPJ, multi-domain crosswalk.
 *
 * <p>Composes three independent gateway domains into a single response:
 * <ul>
 *   <li><b>Empresa</b> — cadastro Receita Federal (cascade BrasilAPI / ReceitaWS / MinhaReceita);</li>
 *   <li><b>Ramo de atividade</b> — descrição oficial CNAE (CONCLA/IBGE) para o {@code cnaePrincipal} retornado;</li>
 *   <li><b>Registro saúde</b> — sinal heurístico, derivado da divisão CNAE: o
 *       {@code CnesService} atual indexa por {@code cnesBase + ibge}, não por
 *       CNPJ, então "registro CNES" aqui é uma classificação setorial. Quando
 *       a divisão CNAE pertence à seção Q (saúde humana e assistência social,
 *       divisões 86/87/88), o response sinaliza que o estabelecimento
 *       <i>deveria</i> estar registrado no CNES e orienta o caminho de consulta.</li>
 * </ul>
 *
 * <p>Pensado para o fluxo de onboarding de clínicas no ERP CerneBR: a partir
 * de um único CNPJ, o ERP recebe dados suficientes para preencher cadastro,
 * sugerir CFOPs corretos, e disparar verificações de compliance específicas
 * do setor saúde quando aplicável.</p>
 */
@Schema(name = "HealthScoreResponse",
        description = "Dossiê B2B agregado de uma pessoa jurídica (CNPJ + CNAE + sinal de setor saúde).")
public record HealthScoreResponse(
        @Schema(description = "Bloco cadastral oficial (Receita Federal)")
        Empresa empresa,

        @Schema(description = "Ramo de atividade econômica (CNAE subclasse, 7 dígitos)")
        RamoAtividade ramoAtividade,

        @Schema(description = "Sinal heurístico de pertencimento ao setor saúde, derivado da divisão CNAE")
        RegistroSaude registroSaude
) {

    @Schema(name = "HealthScoreResponse.Empresa",
            description = "Identidade cadastral da pessoa jurídica.")
    public record Empresa(
            @Schema(description = "CNPJ consultado (somente dígitos)", example = "00000000000191")
            String cnpj,

            @Schema(description = "Razão social", example = "BANCO DO BRASIL SA")
            String razaoSocial,

            @Schema(description = "Nome fantasia", example = "BB")
            String nomeFantasia,

            @Schema(description = "Situação cadastral", example = "ATIVA")
            String situacao,

            @Schema(description = "CEP da sede", example = "70073900")
            String cep,

            @Schema(description = "UF", example = "DF")
            String uf,

            @Schema(description = "Município", example = "Brasília")
            String municipio
    ) {
    }

    @Schema(name = "HealthScoreResponse.RamoAtividade",
            description = "CNAE principal resolvido pela CONCLA/IBGE.")
    public record RamoAtividade(
            @Schema(description = "Código CNAE subclasse (7 dígitos)", example = "8610101")
            String codigo,

            @Schema(description = "Descrição oficial CNAE", example = "ATIVIDADES DE ATENDIMENTO HOSPITALAR, EXCETO PRONTO-SOCORRO E UNIDADES PARA ATENDIMENTO A URGÊNCIAS")
            String descricao
    ) {
    }

    @Schema(name = "HealthScoreResponse.RegistroSaude",
            description = "Indicador de pertencimento ao setor saúde e instrução para consulta CNES quando aplicável.")
    public record RegistroSaude(
            @Schema(description = "Verdadeiro quando a divisão CNAE pertence à seção Q da CONCLA (saúde humana e assistência social).",
                    example = "true")
            boolean setorSaude,

            @Schema(description = "Categoria CNAE detectada quando setorSaude=true; nulo caso contrário.",
                    example = "Atividades de atenção à saúde humana")
            String categoria,

            @Schema(description = "Orientação consumível pelo ERP para o próximo passo (ex.: como buscar CNES quando aplicável).",
                    example = "Estabelecimento provável da rede de saúde — consultar /api/v1/saude/cnes/{cnesBase}/profissionais com o IBGE quando o código CNES estiver disponível.")
            String observacao
    ) {
    }
}
