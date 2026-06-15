package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Papel;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Participacao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Acesso à tabela {@code participacao} — o N:N empresa↔licitação.
 *
 * <p>Escrita em lote idempotente: o {@code ON CONFLICT} aponta para o índice
 * único de expressão {@code ux_part_idem}
 * ({@code licitacao_id, empresa_cnpj, papel, COALESCE(item_sequencial,-1)}),
 * então reprocessar a mesma fase de resultados do PNCP atualiza em vez de
 * duplicar.</p>
 *
 * <p>Os finders abaixo são a base do mapeamento bidirecional (empresa→licitações
 * e licitação→empresas). As consultas de cruzamento com filtros dinâmicos
 * (setor + cidade + ramo + localização) vêm no M3, sobre a materialized view.</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class ParticipacaoRepository {

    private final JdbcTemplate jdbc;

    public ParticipacaoRepository(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String UPSERT = """
            INSERT INTO participacao
                (licitacao_id, empresa_cnpj, papel, item_sequencial, classificacao,
                 valor_proposta, valor_homologado, data_resultado, ano, fonte)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (licitacao_id, empresa_cnpj, papel, COALESCE(item_sequencial, -1)) DO UPDATE SET
                classificacao    = EXCLUDED.classificacao,
                valor_proposta   = EXCLUDED.valor_proposta,
                valor_homologado = EXCLUDED.valor_homologado,
                data_resultado   = EXCLUDED.data_resultado,
                ano              = EXCLUDED.ano,
                fonte            = EXCLUDED.fonte,
                ingerido_em      = now()
            """;

    /** Grava (insert/update) o lote de participações de um certame. */
    public int[] upsertBatch(List<Participacao> participacoes) {
        if (participacoes == null || participacoes.isEmpty()) {
            return new int[0];
        }
        return jdbc.batchUpdate(UPSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Participacao p = participacoes.get(i);
                ps.setLong(1, p.licitacaoId());
                ps.setString(2, p.empresaCnpj());
                ps.setString(3, p.papel().name());
                ps.setObject(4, p.itemSequencial(), Types.INTEGER);
                ps.setObject(5, p.classificacao(), Types.INTEGER);
                ps.setBigDecimal(6, p.valorProposta());
                ps.setBigDecimal(7, p.valorHomologado());
                ps.setObject(8, p.dataResultado(), Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setInt(9, p.ano());
                ps.setString(10, p.fonte() == null ? "pncp" : p.fonte());
            }

            @Override
            public int getBatchSize() {
                return participacoes.size();
            }
        });
    }

    /** Licitação → empresas participantes (sem filtro; base do mapeamento). */
    public List<Participacao> findByLicitacao(long licitacaoId) {
        return jdbc.query(
                "SELECT * FROM participacao WHERE licitacao_id = ? ORDER BY classificacao NULLS LAST",
                MAPPER, licitacaoId);
    }

    /** Empresa → licitações em que participou (mais recentes primeiro). */
    public List<Participacao> findByEmpresa(String cnpj) {
        return jdbc.query(
                "SELECT * FROM participacao WHERE empresa_cnpj = ? ORDER BY data_resultado DESC NULLS LAST",
                MAPPER, cnpj);
    }

    public int countByEmpresa(String cnpj) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM participacao WHERE empresa_cnpj = ?", Integer.class, cnpj);
        return c == null ? 0 : c;
    }

    static final RowMapper<Participacao> MAPPER = (rs, n) -> new Participacao(
            rs.getLong("id"),
            rs.getLong("licitacao_id"),
            rs.getString("empresa_cnpj"),
            Papel.valueOf(rs.getString("papel")),
            getNullableInt(rs, "item_sequencial"),
            getNullableInt(rs, "classificacao"),
            rs.getBigDecimal("valor_proposta"),
            rs.getBigDecimal("valor_homologado"),
            rs.getObject("data_resultado", OffsetDateTime.class),
            rs.getInt("ano"),
            rs.getString("fonte"),
            rs.getObject("ingerido_em", OffsetDateTime.class)
    );

    private static Integer getNullableInt(java.sql.ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
