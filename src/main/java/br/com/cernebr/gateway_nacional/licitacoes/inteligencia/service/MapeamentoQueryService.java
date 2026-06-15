package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.service;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.LicitacaoParticipadaDTO;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.ParticipanteDTO;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest.Cnpjs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mapeamento bidirecional licitação↔empresa. Consulta as TABELAS BASE (não a MV)
 * para devolver dado sempre fresco — são lookups diretos por chave, baratos com
 * os índices {@code ix_part_licitacao} / {@code ix_part_empresa} do M1.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class MapeamentoQueryService {

    private final JdbcTemplate jdbc;

    public MapeamentoQueryService(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Licitação → empresas participantes (uma linha por item participado). */
    public List<ParticipanteDTO> empresasDaLicitacao(String portal, String identificador) {
        return jdbc.query("""
                SELECT e.cnpj, e.razao_social, e.nome_fantasia, e.cnae_principal, e.porte,
                       e.uf, e.municipio_ibge, p.papel, p.item_sequencial,
                       p.valor_homologado, p.data_resultado
                FROM participacao p
                JOIN licitacao l ON l.id = p.licitacao_id
                JOIN empresa   e ON e.cnpj = p.empresa_cnpj
                WHERE l.portal = ? AND l.identificador = ?
                ORDER BY p.valor_homologado DESC NULLS LAST, e.razao_social
                """, PARTICIPANTE, portal, identificador);
    }

    /** Empresa → licitações em que participou (uma linha por edital, agregada). */
    public List<LicitacaoParticipadaDTO> licitacoesDaEmpresa(String cnpjRaw) {
        String cnpj = Cnpjs.normalizar(cnpjRaw);
        if (cnpj == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT l.portal, l.identificador,
                       max(l.numero) numero, max(l.objeto) objeto, max(l.setor) setor,
                       max(l.modalidade) modalidade, max(l.orgao_uf) orgao_uf,
                       max(l.orgao_municipio_ibge) orgao_municipio_ibge,
                       max(l.orgao_municipio_nome) orgao_municipio_nome,
                       max(p.papel) papel, count(*) qtd_itens,
                       sum(p.valor_homologado) valor_total, max(p.data_resultado) data_resultado
                FROM participacao p
                JOIN licitacao l ON l.id = p.licitacao_id
                WHERE p.empresa_cnpj = ?
                GROUP BY l.id, l.portal, l.identificador
                ORDER BY data_resultado DESC NULLS LAST
                """, LICITACAO_PARTICIPADA, cnpj);
    }

    private static final RowMapper<ParticipanteDTO> PARTICIPANTE = (rs, n) -> new ParticipanteDTO(
            rs.getString("cnpj"),
            rs.getString("razao_social"),
            rs.getString("nome_fantasia"),
            rs.getString("cnae_principal"),
            rs.getString("porte"),
            rs.getString("uf"),
            rs.getString("municipio_ibge"),
            rs.getString("papel"),
            getNullableInt(rs, "item_sequencial"),
            rs.getBigDecimal("valor_homologado"),
            rs.getObject("data_resultado", OffsetDateTime.class)
    );

    private static final RowMapper<LicitacaoParticipadaDTO> LICITACAO_PARTICIPADA = (rs, n) -> new LicitacaoParticipadaDTO(
            rs.getString("portal"),
            rs.getString("identificador"),
            rs.getString("numero"),
            rs.getString("objeto"),
            rs.getString("setor"),
            rs.getString("modalidade"),
            rs.getString("orgao_uf"),
            rs.getString("orgao_municipio_ibge"),
            rs.getString("orgao_municipio_nome"),
            rs.getString("papel"),
            rs.getInt("qtd_itens"),
            rs.getBigDecimal("valor_total"),
            rs.getObject("data_resultado", OffsetDateTime.class)
    );

    private static Integer getNullableInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
