package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.EmpresaCnae;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Acesso à tabela {@code empresa_cnae} (CNAEs secundários — ramo de atuação N:N).
 *
 * <p>A estratégia de sincronização é "replace": como o conjunto de CNAEs de um
 * CNPJ vem inteiro do cadastro a cada enriquecimento, apagamos e regravamos em
 * vez de fazer diff — mais simples e correto. Chame dentro da transação do ETL
 * ({@code licIntelTxTemplate}) para manter atômico com o upsert da empresa.</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class EmpresaCnaeRepository {

    private final JdbcTemplate jdbc;

    public EmpresaCnaeRepository(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Substitui todos os CNAEs de um CNPJ pelo conjunto informado. */
    public void replaceForEmpresa(String cnpj, List<EmpresaCnae> cnaes) {
        jdbc.update("DELETE FROM empresa_cnae WHERE cnpj = ?", cnpj);
        if (cnaes == null || cnaes.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO empresa_cnae (cnpj, cnae, principal) VALUES (?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        EmpresaCnae c = cnaes.get(i);
                        ps.setString(1, c.cnpj());
                        ps.setString(2, c.cnae());
                        ps.setBoolean(3, c.principal());
                    }

                    @Override
                    public int getBatchSize() {
                        return cnaes.size();
                    }
                });
    }

    public List<EmpresaCnae> findByCnpj(String cnpj) {
        return jdbc.query("SELECT cnpj, cnae, principal FROM empresa_cnae WHERE cnpj = ? ORDER BY cnae",
                MAPPER, cnpj);
    }

    static final RowMapper<EmpresaCnae> MAPPER = (rs, n) -> new EmpresaCnae(
            rs.getString("cnpj"),
            rs.getString("cnae"),
            rs.getBoolean("principal")
    );
}
