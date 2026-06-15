package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Licitacao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Acesso à tabela {@code licitacao} (snapshot persistido do edital). O upsert é
 * keyed por {@code (portal, identificador)} e devolve o {@code id} (surrogate)
 * via {@code RETURNING} — o ETL usa esse id como FK em {@code participacao}.
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class LicitacaoIntelRepository {

    private final JdbcTemplate jdbc;

    public LicitacaoIntelRepository(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String UPSERT = """
            INSERT INTO licitacao
                (portal, identificador, numero, objeto, modalidade, setor,
                 orgao_cnpj, orgao_nome, orgao_uf, orgao_municipio_nome,
                 orgao_municipio_ibge, valor_estimado, valor_homologado,
                 data_abertura, data_resultado, ano, situacao, fonte)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (portal, identificador) DO UPDATE SET
                numero               = EXCLUDED.numero,
                objeto               = EXCLUDED.objeto,
                modalidade           = EXCLUDED.modalidade,
                setor                = EXCLUDED.setor,
                orgao_cnpj           = EXCLUDED.orgao_cnpj,
                orgao_nome           = EXCLUDED.orgao_nome,
                orgao_uf             = EXCLUDED.orgao_uf,
                orgao_municipio_nome = EXCLUDED.orgao_municipio_nome,
                orgao_municipio_ibge = EXCLUDED.orgao_municipio_ibge,
                valor_estimado       = EXCLUDED.valor_estimado,
                valor_homologado     = EXCLUDED.valor_homologado,
                data_abertura        = EXCLUDED.data_abertura,
                data_resultado       = EXCLUDED.data_resultado,
                ano                  = EXCLUDED.ano,
                situacao             = EXCLUDED.situacao,
                fonte                = EXCLUDED.fonte,
                ingerido_em          = now()
            RETURNING id
            """;

    /** Insere ou atualiza o edital e devolve a PK surrogate. */
    public long upsert(Licitacao l) {
        Long id = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(UPSERT);
            bind(ps, l);
            return ps;
        }, rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException(
                    "upsert de licitacao não devolveu id para " + l.portal() + "/" + l.identificador());
        }
        return id;
    }

    public Optional<Long> findId(String portal, String identificador) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id FROM licitacao WHERE portal = ? AND identificador = ?",
                    Long.class, portal, identificador));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<Licitacao> findByPortalIdentificador(String portal, String identificador) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM licitacao WHERE portal = ? AND identificador = ?",
                    MAPPER, portal, identificador));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static void bind(PreparedStatement ps, Licitacao l) throws java.sql.SQLException {
        ps.setString(1, l.portal());
        ps.setString(2, l.identificador());
        ps.setString(3, l.numero());
        ps.setString(4, l.objeto());
        ps.setString(5, l.modalidade());
        ps.setString(6, l.setor());
        ps.setString(7, l.orgaoCnpj());
        ps.setString(8, l.orgaoNome());
        ps.setString(9, l.orgaoUf());
        ps.setString(10, l.orgaoMunicipioNome());
        ps.setString(11, l.orgaoMunicipioIbge());
        ps.setBigDecimal(12, l.valorEstimado());
        ps.setBigDecimal(13, l.valorHomologado());
        ps.setObject(14, l.dataAbertura(), Types.TIMESTAMP_WITH_TIMEZONE);
        ps.setObject(15, l.dataResultado(), Types.TIMESTAMP_WITH_TIMEZONE);
        ps.setInt(16, l.ano());
        ps.setString(17, l.situacao());
        ps.setString(18, l.fonte() == null ? "pncp" : l.fonte());
    }

    static final RowMapper<Licitacao> MAPPER = (rs, n) -> new Licitacao(
            rs.getLong("id"),
            rs.getString("portal"),
            rs.getString("identificador"),
            rs.getString("numero"),
            rs.getString("objeto"),
            rs.getString("modalidade"),
            rs.getString("setor"),
            rs.getString("orgao_cnpj"),
            rs.getString("orgao_nome"),
            rs.getString("orgao_uf"),
            rs.getString("orgao_municipio_nome"),
            rs.getString("orgao_municipio_ibge"),
            rs.getBigDecimal("valor_estimado"),
            rs.getBigDecimal("valor_homologado"),
            rs.getObject("data_abertura", OffsetDateTime.class),
            rs.getObject("data_resultado", OffsetDateTime.class),
            rs.getInt("ano"),
            rs.getString("situacao"),
            rs.getString("fonte"),
            rs.getObject("ingerido_em", OffsetDateTime.class)
    );
}
