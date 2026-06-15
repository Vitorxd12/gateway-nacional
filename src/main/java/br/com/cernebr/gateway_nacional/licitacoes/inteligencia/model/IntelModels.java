package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Modelos de domínio imutáveis da Inteligência de Licitações — plain Java
 * records, sem dependência de JPA/Hibernate. Mesma escolha do SIGTAP
 * ({@code SigtapModels}): records dão imutabilidade, equals/hashCode automáticos
 * e encaixam direto nos {@code RowMapper}s sem boilerplate.
 *
 * <p>Cada record corresponde a uma linha conceitual de uma tabela Postgres
 * gerenciada pelo {@code IntelSchemaBootstrap} e manipulada pelos repositórios.</p>
 */
public final class IntelModels {

    private IntelModels() {
    }

    /**
     * Papel da empresa no certame. Distingue quem só disputou de quem fechou —
     * o CRM normalmente prospecta {@code VENCEDOR}/{@code HOMOLOGADO}, mas
     * {@code PROPONENTE} serve para mapear quem atua no mercado.
     */
    public enum Papel {
        PROPONENTE, HABILITADO, VENCEDOR, HOMOLOGADO, DESCLASSIFICADO
    }

    public record Empresa(
            String cnpj,
            String razaoSocial,
            String nomeFantasia,
            String cnaePrincipal,
            String porte,
            String naturezaJuridica,
            String uf,
            String municipioNome,
            String municipioIbge,
            String situacao,
            OffsetDateTime enriquecidoEm,
            OffsetDateTime atualizadoEm
    ) {
    }

    public record EmpresaCnae(
            String cnpj,
            String cnae,
            boolean principal
    ) {
    }

    public record Licitacao(
            Long id,
            String portal,
            String identificador,
            String numero,
            String objeto,
            String modalidade,
            String setor,
            String orgaoCnpj,
            String orgaoNome,
            String orgaoUf,
            String orgaoMunicipioNome,
            String orgaoMunicipioIbge,
            BigDecimal valorEstimado,
            BigDecimal valorHomologado,
            OffsetDateTime dataAbertura,
            OffsetDateTime dataResultado,
            int ano,
            String situacao,
            String fonte,
            OffsetDateTime ingeridoEm
    ) {
    }

    public record Participacao(
            Long id,
            long licitacaoId,
            String empresaCnpj,
            Papel papel,
            Integer itemSequencial,
            Integer classificacao,
            BigDecimal valorProposta,
            BigDecimal valorHomologado,
            OffsetDateTime dataResultado,
            int ano,
            String fonte,
            OffsetDateTime ingeridoEm
    ) {
    }
}
