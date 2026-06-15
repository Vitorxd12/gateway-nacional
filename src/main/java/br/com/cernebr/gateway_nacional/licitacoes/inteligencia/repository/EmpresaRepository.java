package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Empresa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acesso à tabela {@code empresa} (mestre de fornecedor). Idempotente via
 * {@code INSERT ... ON CONFLICT (cnpj) DO UPDATE} — o ETL pode re-enriquecer o
 * mesmo CNPJ sem duplicar nem perder a referência usada por {@code participacao}.
 *
 * <p>Conditional ao mesmo flag do {@code IntelDataSourceConfig}: não existe
 * quando {@code gateway.licitacoes.inteligencia.enabled=false}.</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class EmpresaRepository {

    private final JdbcTemplate jdbc;

    public EmpresaRepository(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String UPSERT = """
            INSERT INTO empresa
                (cnpj, razao_social, nome_fantasia, cnae_principal, porte,
                 natureza_juridica, uf, municipio_nome, municipio_ibge, situacao,
                 enriquecido_em)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (cnpj) DO UPDATE SET
                razao_social      = EXCLUDED.razao_social,
                nome_fantasia     = EXCLUDED.nome_fantasia,
                cnae_principal    = EXCLUDED.cnae_principal,
                porte             = EXCLUDED.porte,
                natureza_juridica = EXCLUDED.natureza_juridica,
                uf                = EXCLUDED.uf,
                municipio_nome    = EXCLUDED.municipio_nome,
                municipio_ibge    = EXCLUDED.municipio_ibge,
                situacao          = EXCLUDED.situacao,
                enriquecido_em    = EXCLUDED.enriquecido_em,
                atualizado_em     = now()
            """;

    /** Insere ou atualiza a empresa. {@code atualizado_em} é gerido pelo banco. */
    public void upsert(Empresa e) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(UPSERT);
            ps.setString(1, e.cnpj());
            ps.setString(2, e.razaoSocial());
            ps.setString(3, e.nomeFantasia());
            ps.setString(4, e.cnaePrincipal());
            ps.setString(5, e.porte());
            ps.setString(6, e.naturezaJuridica());
            ps.setString(7, e.uf());
            ps.setString(8, e.municipioNome());
            ps.setString(9, e.municipioIbge());
            ps.setString(10, e.situacao());
            ps.setObject(11, e.enriquecidoEm(), Types.TIMESTAMP_WITH_TIMEZONE);
            return ps;
        });
    }

    public Optional<Empresa> findByCnpj(String cnpj) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT * FROM empresa WHERE cnpj = ?", MAPPER, cnpj));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Indica se a empresa já foi enriquecida depois de {@code limite} — o ETL usa
     * para pular re-lookup de CNPJ recente e poupar a quota de ReceitaWS/BrasilAPI.
     */
    public boolean enriquecidaDesde(String cnpj, OffsetDateTime limite) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM empresa WHERE cnpj = ? AND enriquecido_em >= ?)",
                Boolean.class, cnpj, limite);
        return Boolean.TRUE.equals(exists);
    }

    /** Amostra das empresas enriquecidas mais recentes — validação do ETL. */
    public List<Empresa> amostra(int limit) {
        return jdbc.query(
                "SELECT * FROM empresa ORDER BY enriquecido_em DESC NULLS LAST LIMIT ?",
                MAPPER, limit);
    }

    /**
     * Grava o stub mínimo (CNPJ + razão) se a empresa ainda não existe. NÃO
     * sobrescreve dados já enriquecidos ({@code DO NOTHING}). Usado pela ingestão
     * rápida — o CNAE/IBGE vêm depois pelo worker assíncrono (M2.5).
     */
    public void insertStubIfAbsent(String cnpj, String razaoSocial) {
        String razao = razaoSocial != null && !razaoSocial.isBlank() ? razaoSocial : "CNPJ " + cnpj;
        jdbc.update("INSERT INTO empresa (cnpj, razao_social) VALUES (?, ?) ON CONFLICT (cnpj) DO NOTHING",
                cnpj, razao);
    }

    /**
     * CNPJs pendentes de enriquecimento (sem CNAE), priorizando os nunca
     * tentados e depois os mais antigos. O worker processa em lotes pequenos.
     */
    public List<String> findPendentes(int limit) {
        return jdbc.queryForList(
                "SELECT cnpj FROM empresa WHERE cnae_principal IS NULL "
                        + "ORDER BY enriquecido_em ASC NULLS FIRST, atualizado_em ASC LIMIT ?",
                String.class, limit);
    }

    /** Total pendente de enriquecimento (métrica/log). */
    public int countPendentes() {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM empresa WHERE cnae_principal IS NULL", Integer.class);
        return c == null ? 0 : c;
    }

    /**
     * Marca uma tentativa de enriquecimento sem sucesso — só toca {@code
     * atualizado_em} para o worker mandar este CNPJ ao fim da fila e não ficar
     * preso nele (throttle do provider é transitório; re-tenta depois).
     */
    public void marcarTentativaFalha(String cnpj) {
        jdbc.update("UPDATE empresa SET atualizado_em = now() WHERE cnpj = ?", cnpj);
    }

    static final RowMapper<Empresa> MAPPER = (rs, n) -> new Empresa(
            rs.getString("cnpj"),
            rs.getString("razao_social"),
            rs.getString("nome_fantasia"),
            rs.getString("cnae_principal"),
            rs.getString("porte"),
            rs.getString("natureza_juridica"),
            rs.getString("uf"),
            rs.getString("municipio_nome"),
            rs.getString("municipio_ibge"),
            rs.getString("situacao"),
            rs.getObject("enriquecido_em", OffsetDateTime.class),
            rs.getObject("atualizado_em", OffsetDateTime.class)
    );
}
