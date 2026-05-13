package br.com.cernebr.gateway_nacional.saude.sigtap.jdbc;

import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cid;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Dataset;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.DatasetStatus;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Procedimento;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Camada única de acesso ao SQLite SIGTAP. Encapsula:
 * <ul>
 *   <li>Bootstrap programático do schema via {@code CREATE TABLE IF NOT EXISTS}
 *       — chamado pelo ETL na primeira ingestão; nunca pelo boot.</li>
 *   <li>Operações de escrita em lote (batch JDBC) durante a ingestão.</li>
 *   <li>Operações de leitura otimizadas para os endpoints REST.</li>
 *   <li>O switch atômico Blue-Green (UPDATE em transação única).</li>
 * </ul>
 *
 * <p>Toda a classe é {@link ConditionalOnProperty conditional} ao mesmo
 * flag do {@code SigtapDataSourceConfig} — não existe quando o cron
 * está desabilitado.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapJdbc {

    private final JdbcTemplate jdbc;

    public SigtapJdbc(@Qualifier("sigtapJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Schema bootstrap — programático, idempotente, sem migrations.
    // ──────────────────────────────────────────────────────────────────
    public void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_dataset (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    competencia   TEXT    NOT NULL,
                    revisao       TEXT,
                    status        TEXT    NOT NULL CHECK (status IN ('STAGING','ACTIVE','ARCHIVED','FAILED')),
                    started_at    TEXT    NOT NULL,
                    promoted_at   TEXT,
                    archived_at   TEXT,
                    source_url    TEXT,
                    notes         TEXT
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_dataset_comp_status ON sigtap_dataset(competencia, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_dataset_status ON sigtap_dataset(status)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_grupo (
                    dataset_id INTEGER NOT NULL,
                    codigo     TEXT    NOT NULL,
                    nome       TEXT    NOT NULL,
                    PRIMARY KEY (dataset_id, codigo)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_subgrupo (
                    dataset_id    INTEGER NOT NULL,
                    codigo        TEXT    NOT NULL,
                    grupo_codigo  TEXT    NOT NULL,
                    nome          TEXT    NOT NULL,
                    PRIMARY KEY (dataset_id, codigo)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_forma_organizacao (
                    dataset_id       INTEGER NOT NULL,
                    codigo           TEXT    NOT NULL,
                    subgrupo_codigo  TEXT    NOT NULL,
                    nome             TEXT    NOT NULL,
                    PRIMARY KEY (dataset_id, codigo)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_procedimento (
                    dataset_id                INTEGER NOT NULL,
                    codigo                    TEXT    NOT NULL,
                    nome                      TEXT    NOT NULL,
                    complexidade              TEXT,
                    sexo                      TEXT,
                    idade_minima_dias         INTEGER,
                    idade_maxima_dias         INTEGER,
                    quantidade_maxima         INTEGER,
                    tipo_financiamento        TEXT,
                    valor_sa                  REAL    DEFAULT 0,
                    valor_sh                  REAL    DEFAULT 0,
                    valor_sp                  REAL    DEFAULT 0,
                    grupo_codigo              TEXT,
                    subgrupo_codigo           TEXT,
                    forma_organizacao_codigo  TEXT,
                    dt_competencia            TEXT    NOT NULL,
                    PRIMARY KEY (dataset_id, codigo)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_proc_ds_nome ON sigtap_procedimento(dataset_id, nome)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_proc_ds_grupo ON sigtap_procedimento(dataset_id, grupo_codigo)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_cbo (
                    dataset_id INTEGER NOT NULL,
                    codigo     TEXT    NOT NULL,
                    nome       TEXT    NOT NULL,
                    PRIMARY KEY (dataset_id, codigo)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_cid (
                    dataset_id INTEGER NOT NULL,
                    codigo     TEXT    NOT NULL,
                    nome       TEXT    NOT NULL,
                    PRIMARY KEY (dataset_id, codigo)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_procedimento_cbo (
                    dataset_id           INTEGER NOT NULL,
                    procedimento_codigo  TEXT    NOT NULL,
                    cbo_codigo           TEXT    NOT NULL,
                    PRIMARY KEY (dataset_id, procedimento_codigo, cbo_codigo)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_pcbo_ds_cbo ON sigtap_procedimento_cbo(dataset_id, cbo_codigo)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sigtap_procedimento_cid (
                    dataset_id           INTEGER NOT NULL,
                    procedimento_codigo  TEXT    NOT NULL,
                    cid_codigo           TEXT    NOT NULL,
                    obrigatorio          INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (dataset_id, procedimento_codigo, cid_codigo)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_pcid_ds_cid ON sigtap_procedimento_cid(dataset_id, cid_codigo)");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Dataset lifecycle (Blue-Green)
    // ──────────────────────────────────────────────────────────────────
    public long createStagingDataset(String competencia, String revisao, String sourceUrl) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // KeyHolder captura a PK gerada na MESMA conexão do INSERT.
        // Sem pool (SimpleDriverDataSource), cada chamada do JdbcTemplate
        // pega uma conexão nova — last_insert_rowid() em chamada separada
        // viria zero por executar em sessão sem histórico de insert.
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement("""
                    INSERT INTO sigtap_dataset (competencia, revisao, status, started_at, source_url)
                    VALUES (?, ?, 'STAGING', ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, competencia);
            if (revisao == null) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, revisao);
            ps.setString(3, now.toString());
            if (sourceUrl == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, sourceUrl);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("INSERT INTO sigtap_dataset não devolveu PK gerada.");
        }
        return key.longValue();
    }

    /**
     * Atomic switch: arquiva o ACTIVE atual da mesma competência e promove
     * o STAGING informado a ACTIVE. Uma única transação SQLite garante que
     * nenhum leitor enxergue um estado intermediário.
     */
    public void promoteStagingToActive(long datasetId, String competencia) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE sigtap_dataset
                   SET status='ARCHIVED', archived_at=?
                 WHERE status='ACTIVE'
                """, now.toString());
        jdbc.update("""
                UPDATE sigtap_dataset
                   SET status='ACTIVE', promoted_at=?
                 WHERE id=? AND competencia=?
                """, now.toString(), datasetId, competencia);
    }

    public void markFailed(long datasetId, String reason) {
        jdbc.update("""
                UPDATE sigtap_dataset
                   SET status='FAILED', notes=?
                 WHERE id=?
                """, reason, datasetId);
    }

    public Optional<Dataset> findActive() {
        return queryDatasetSingle("WHERE status='ACTIVE'");
    }

    public Optional<Dataset> findStaging() {
        return queryDatasetSingle("WHERE status='STAGING'");
    }

    public boolean hasActiveForCompetencia(String competencia) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sigtap_dataset WHERE competencia=? AND status='ACTIVE'",
                Integer.class, competencia);
        return c != null && c > 0;
    }

    private Optional<Dataset> queryDatasetSingle(String whereClause) {
        try {
            Dataset ds = jdbc.queryForObject(
                    "SELECT * FROM sigtap_dataset " + whereClause + " ORDER BY id DESC LIMIT 1",
                    DATASET_MAPPER);
            return Optional.ofNullable(ds);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Batch inserts — usados pelo ETL durante a ingestão posicional
    // ──────────────────────────────────────────────────────────────────
    public void batchInsertProcedimentos(long datasetId, List<Procedimento> rows) {
        jdbc.batchUpdate("""
                INSERT OR REPLACE INTO sigtap_procedimento
                (dataset_id, codigo, nome, complexidade, sexo, idade_minima_dias, idade_maxima_dias,
                 quantidade_maxima, tipo_financiamento, valor_sa, valor_sh, valor_sp,
                 grupo_codigo, subgrupo_codigo, forma_organizacao_codigo, dt_competencia)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Procedimento p = rows.get(i);
                ps.setLong(1, datasetId);
                ps.setString(2, p.codigo());
                ps.setString(3, p.nome());
                setNullableString(ps, 4, p.complexidade());
                setNullableString(ps, 5, p.sexo());
                setNullableInt(ps, 6, p.idadeMinimaDias());
                setNullableInt(ps, 7, p.idadeMaximaDias());
                setNullableInt(ps, 8, p.quantidadeMaxima());
                setNullableString(ps, 9, p.tipoFinanciamento());
                ps.setBigDecimal(10, nz(p.valorSa()));
                ps.setBigDecimal(11, nz(p.valorSh()));
                ps.setBigDecimal(12, nz(p.valorSp()));
                setNullableString(ps, 13, p.grupoCodigo());
                setNullableString(ps, 14, p.subgrupoCodigo());
                setNullableString(ps, 15, p.formaOrganizacaoCodigo());
                ps.setString(16, p.dtCompetencia());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public void batchInsertCbos(long datasetId, List<Cbo> rows) {
        jdbc.batchUpdate("INSERT OR REPLACE INTO sigtap_cbo (dataset_id, codigo, nome) VALUES (?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Cbo c = rows.get(i);
                        ps.setLong(1, datasetId);
                        ps.setString(2, c.codigo());
                        ps.setString(3, c.nome());
                    }
                    @Override public int getBatchSize() { return rows.size(); }
                });
    }

    public void batchInsertCids(long datasetId, List<Cid> rows) {
        jdbc.batchUpdate("INSERT OR REPLACE INTO sigtap_cid (dataset_id, codigo, nome) VALUES (?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Cid c = rows.get(i);
                        ps.setLong(1, datasetId);
                        ps.setString(2, c.codigo());
                        ps.setString(3, c.nome());
                    }
                    @Override public int getBatchSize() { return rows.size(); }
                });
    }

    public void batchInsertProcCbo(long datasetId, List<ProcedimentoCbo> rows) {
        jdbc.batchUpdate("""
                INSERT OR REPLACE INTO sigtap_procedimento_cbo
                (dataset_id, procedimento_codigo, cbo_codigo) VALUES (?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ProcedimentoCbo r = rows.get(i);
                ps.setLong(1, datasetId);
                ps.setString(2, r.procedimentoCodigo());
                ps.setString(3, r.cboCodigo());
            }
            @Override public int getBatchSize() { return rows.size(); }
        });
    }

    public void batchInsertProcCid(long datasetId, List<ProcedimentoCid> rows) {
        jdbc.batchUpdate("""
                INSERT OR REPLACE INTO sigtap_procedimento_cid
                (dataset_id, procedimento_codigo, cid_codigo, obrigatorio) VALUES (?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ProcedimentoCid r = rows.get(i);
                ps.setLong(1, datasetId);
                ps.setString(2, r.procedimentoCodigo());
                ps.setString(3, r.cidCodigo());
                ps.setInt(4, r.obrigatorio() ? 1 : 0);
            }
            @Override public int getBatchSize() { return rows.size(); }
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  Leituras consumidas pelo SigtapService
    // ──────────────────────────────────────────────────────────────────
    public Optional<Procedimento> findProcedimento(long datasetId, String codigo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM sigtap_procedimento WHERE dataset_id=? AND codigo=?",
                    PROCEDIMENTO_MAPPER, datasetId, codigo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<Procedimento> buscarProcedimentos(long datasetId, String termo, int limit) {
        String like = "%" + termo.toUpperCase() + "%";
        return jdbc.query("""
                SELECT * FROM sigtap_procedimento
                 WHERE dataset_id=?
                   AND (UPPER(nome) LIKE ? OR codigo LIKE ?)
                 ORDER BY nome ASC
                 LIMIT ?
                """, PROCEDIMENTO_MAPPER, datasetId, like, termo + "%", limit);
    }

    public List<String> cbosDoProcedimento(long datasetId, String procedimentoCodigo) {
        return jdbc.queryForList("""
                SELECT cbo_codigo FROM sigtap_procedimento_cbo
                 WHERE dataset_id=? AND procedimento_codigo=?
                 ORDER BY cbo_codigo ASC
                """, String.class, datasetId, procedimentoCodigo);
    }

    public List<String> procedimentosDoCbo(long datasetId, String cboCodigo) {
        return jdbc.queryForList("""
                SELECT procedimento_codigo FROM sigtap_procedimento_cbo
                 WHERE dataset_id=? AND cbo_codigo=?
                 ORDER BY procedimento_codigo ASC
                """, String.class, datasetId, cboCodigo);
    }

    public List<ProcedimentoCid> cidsDoProcedimento(long datasetId, String procedimentoCodigo) {
        return jdbc.query("""
                SELECT * FROM sigtap_procedimento_cid
                 WHERE dataset_id=? AND procedimento_codigo=?
                 ORDER BY cid_codigo ASC
                """, PROC_CID_MAPPER, datasetId, procedimentoCodigo);
    }

    public List<String> procedimentosDoCid(long datasetId, String cidCodigo) {
        return jdbc.queryForList("""
                SELECT procedimento_codigo FROM sigtap_procedimento_cid
                 WHERE dataset_id=? AND cid_codigo=?
                 ORDER BY procedimento_codigo ASC
                """, String.class, datasetId, cidCodigo);
    }

    public List<Procedimento> rankProcedimentosPorValor(long datasetId, String grupo, boolean asc, int limit) {
        String orderDir = asc ? "ASC" : "DESC";
        String sql;
        Object[] args;
        if (grupo == null || grupo.isBlank()) {
            sql = "SELECT * FROM sigtap_procedimento WHERE dataset_id=? " +
                    "ORDER BY (COALESCE(valor_sa,0)+COALESCE(valor_sh,0)+COALESCE(valor_sp,0)) " + orderDir + " LIMIT ?";
            args = new Object[]{datasetId, limit};
        } else {
            sql = "SELECT * FROM sigtap_procedimento WHERE dataset_id=? AND grupo_codigo=? " +
                    "ORDER BY (COALESCE(valor_sa,0)+COALESCE(valor_sh,0)+COALESCE(valor_sp,0)) " + orderDir + " LIMIT ?";
            args = new Object[]{datasetId, grupo, limit};
        }
        return jdbc.query(sql, PROCEDIMENTO_MAPPER, args);
    }

    public Optional<Cbo> findCbo(long datasetId, String codigo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM sigtap_cbo WHERE dataset_id=? AND codigo=?",
                    CBO_MAPPER, datasetId, codigo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<Cid> findCid(long datasetId, String codigo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM sigtap_cid WHERE dataset_id=? AND codigo=?",
                    CID_MAPPER, datasetId, codigo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<Procedimento> listAllProcedimentos(long datasetId) {
        return jdbc.query("SELECT * FROM sigtap_procedimento WHERE dataset_id=? ORDER BY codigo",
                PROCEDIMENTO_MAPPER, datasetId);
    }

    public List<Cbo> listAllCbos(long datasetId) {
        return jdbc.query("SELECT * FROM sigtap_cbo WHERE dataset_id=? ORDER BY codigo", CBO_MAPPER, datasetId);
    }

    public List<Cid> listAllCids(long datasetId) {
        return jdbc.query("SELECT * FROM sigtap_cid WHERE dataset_id=? ORDER BY codigo", CID_MAPPER, datasetId);
    }

    public List<ProcedimentoCbo> listAllProcCbo(long datasetId) {
        return jdbc.query("SELECT * FROM sigtap_procedimento_cbo WHERE dataset_id=?", (rs, n) ->
                new ProcedimentoCbo(rs.getLong("dataset_id"),
                        rs.getString("procedimento_codigo"),
                        rs.getString("cbo_codigo")), datasetId);
    }

    public List<ProcedimentoCid> listAllProcCid(long datasetId) {
        return jdbc.query("SELECT * FROM sigtap_procedimento_cid WHERE dataset_id=?", PROC_CID_MAPPER, datasetId);
    }

    public int contarProcedimentos(long datasetId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sigtap_procedimento WHERE dataset_id=?",
                Integer.class, datasetId);
        return c == null ? 0 : c;
    }

    // ──────────────────────────────────────────────────────────────────
    //  RowMappers + helpers
    // ──────────────────────────────────────────────────────────────────
    private static final RowMapper<Dataset> DATASET_MAPPER = (rs, n) -> new Dataset(
            rs.getLong("id"),
            rs.getString("competencia"),
            rs.getString("revisao"),
            DatasetStatus.valueOf(rs.getString("status")),
            parseTs(rs.getString("started_at")),
            parseTs(rs.getString("promoted_at")),
            parseTs(rs.getString("archived_at")),
            rs.getString("source_url"),
            rs.getString("notes")
    );

    private static final RowMapper<Procedimento> PROCEDIMENTO_MAPPER = (rs, n) -> new Procedimento(
            rs.getLong("dataset_id"),
            rs.getString("codigo"),
            rs.getString("nome"),
            rs.getString("complexidade"),
            rs.getString("sexo"),
            getNullableInt(rs, "idade_minima_dias"),
            getNullableInt(rs, "idade_maxima_dias"),
            getNullableInt(rs, "quantidade_maxima"),
            rs.getString("tipo_financiamento"),
            getDecimal(rs, "valor_sa"),
            getDecimal(rs, "valor_sh"),
            getDecimal(rs, "valor_sp"),
            rs.getString("grupo_codigo"),
            rs.getString("subgrupo_codigo"),
            rs.getString("forma_organizacao_codigo"),
            rs.getString("dt_competencia")
    );

    private static final RowMapper<Cbo> CBO_MAPPER = (rs, n) -> new Cbo(
            rs.getLong("dataset_id"), rs.getString("codigo"), rs.getString("nome"));

    private static final RowMapper<Cid> CID_MAPPER = (rs, n) -> new Cid(
            rs.getLong("dataset_id"), rs.getString("codigo"), rs.getString("nome"));

    private static final RowMapper<ProcedimentoCid> PROC_CID_MAPPER = (rs, n) -> new ProcedimentoCid(
            rs.getLong("dataset_id"),
            rs.getString("procedimento_codigo"),
            rs.getString("cid_codigo"),
            rs.getInt("obrigatorio") == 1);

    private static OffsetDateTime parseTs(String iso) {
        if (iso == null) return null;
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer getNullableInt(java.sql.ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static BigDecimal getDecimal(java.sql.ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        if (rs.wasNull()) return null;
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void setNullableString(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, value);
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) ps.setNull(idx, Types.INTEGER);
        else ps.setInt(idx, value);
    }
}
