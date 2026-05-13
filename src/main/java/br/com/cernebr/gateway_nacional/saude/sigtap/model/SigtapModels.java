package br.com.cernebr.gateway_nacional.saude.sigtap.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Modelos de domínio imutáveis do SIGTAP — plain Java records, sem
 * dependência de JPA/Hibernate.
 *
 * <p>Cada record representa uma linha conceitual em uma tabela SQLite
 * gerenciada pelo {@code SigtapJdbc} (que cria/lê via JdbcTemplate).
 * Manter os modelos como records garante imutabilidade, equals/hashCode
 * automáticos e cabe natural nos {@code RowMapper}s sem boilerplate.</p>
 */
public final class SigtapModels {

    private SigtapModels() {
    }

    public enum DatasetStatus { STAGING, ACTIVE, ARCHIVED, FAILED }

    public record Dataset(
            Long id,
            String competencia,
            String revisao,
            DatasetStatus status,
            OffsetDateTime startedAt,
            OffsetDateTime promotedAt,
            OffsetDateTime archivedAt,
            String sourceUrl,
            String notes
    ) {
    }

    public record Procedimento(
            Long datasetId,
            String codigo,
            String nome,
            String complexidade,
            String sexo,
            Integer idadeMinimaDias,
            Integer idadeMaximaDias,
            Integer quantidadeMaxima,
            String tipoFinanciamento,
            BigDecimal valorSa,
            BigDecimal valorSh,
            BigDecimal valorSp,
            String grupoCodigo,
            String subgrupoCodigo,
            String formaOrganizacaoCodigo,
            String dtCompetencia
    ) {
        public BigDecimal valorTotal() {
            BigDecimal sa = valorSa == null ? BigDecimal.ZERO : valorSa;
            BigDecimal sh = valorSh == null ? BigDecimal.ZERO : valorSh;
            BigDecimal sp = valorSp == null ? BigDecimal.ZERO : valorSp;
            return sa.add(sh).add(sp);
        }
    }

    public record Cbo(Long datasetId, String codigo, String nome) {
    }

    public record Cid(Long datasetId, String codigo, String nome) {
    }

    public record Grupo(Long datasetId, String codigo, String nome) {
    }

    public record Subgrupo(Long datasetId, String codigo, String grupoCodigo, String nome) {
    }

    public record FormaOrganizacao(Long datasetId, String codigo, String subgrupoCodigo, String nome) {
    }

    public record ProcedimentoCbo(Long datasetId, String procedimentoCodigo, String cboCodigo) {
    }

    public record ProcedimentoCid(Long datasetId, String procedimentoCodigo, String cidCodigo, boolean obrigatorio) {
    }
}
