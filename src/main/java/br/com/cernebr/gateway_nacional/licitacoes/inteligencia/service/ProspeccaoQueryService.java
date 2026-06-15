package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.service;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.EmpresaProspeccaoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.FiltroProspeccao;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.ProspeccaoPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Motor de consultas analíticas do CRM (M3). Roda sobre a materialized view
 * {@code mv_prospeccao} (read-model achatado), agregando por empresa e aplicando
 * filtros dinâmicos das duas faces (edital + empresa). Paginação por LIMIT/OFFSET.
 *
 * <p>Usa {@link NamedParameterJdbcTemplate} para montar o WHERE condicional sem
 * concatenação de valores (anti-injection), mantendo o idioma JdbcTemplate do
 * projeto.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class ProspeccaoQueryService {

    private static final int SIZE_DEFAULT = 20;
    private static final int SIZE_MAX = 200;

    private final NamedParameterJdbcTemplate npjt;

    public ProspeccaoQueryService(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.npjt = new NamedParameterJdbcTemplate(jdbc);
    }

    public ProspeccaoPage prospectar(FiltroProspeccao f) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        MapSqlParameterSource p = new MapSqlParameterSource();

        // face EDITAL
        eq(where, p, "setor", f.setor());
        eq(where, p, "edital_municipio_ibge", f.municipioOrgaoIbge(), "munOrgao");
        eq(where, p, "edital_uf", f.ufOrgao(), "ufOrgao");
        eq(where, p, "papel", f.papel());
        if (notBlank(f.modalidade())) {
            where.append(" AND modalidade ILIKE :modalidade ");
            p.addValue("modalidade", "%" + f.modalidade().trim() + "%");
        }
        if (f.dataDe() != null) {
            where.append(" AND data_resultado >= :dataDe ");
            p.addValue("dataDe", f.dataDe());
        }
        if (f.dataAte() != null) {
            where.append(" AND data_resultado <= :dataAte ");
            p.addValue("dataAte", f.dataAte());
        }
        if (f.valorMin() != null) {
            where.append(" AND valor_homologado >= :valorMin ");
            p.addValue("valorMin", f.valorMin());
        }
        if (f.valorMax() != null) {
            where.append(" AND valor_homologado <= :valorMax ");
            p.addValue("valorMax", f.valorMax());
        }
        // face EMPRESA
        if (notBlank(f.cnaeEmpresa())) {
            where.append(" AND cnae_principal LIKE :cnaeEmpresa ");
            p.addValue("cnaeEmpresa", f.cnaeEmpresa().trim() + "%"); // prefixo (divisão/grupo/subclasse)
        }
        eq(where, p, "empresa_uf", f.ufEmpresa(), "ufEmpresa");
        eq(where, p, "empresa_municipio_ibge", f.municipioEmpresaIbge(), "munEmpresa");

        Long total = npjt.queryForObject(
                "SELECT count(DISTINCT cnpj) FROM mv_prospeccao" + where, p, Long.class);
        long totalEmpresas = total == null ? 0 : total;

        int size = f.size() <= 0 ? SIZE_DEFAULT : Math.min(f.size(), SIZE_MAX);
        int page = Math.max(f.page(), 0);
        p.addValue("lim", size);
        p.addValue("off", (long) page * size);

        String sql = "SELECT cnpj,"
                + " max(razao_social) razao, max(nome_fantasia) fantasia, max(cnae_principal) cnae,"
                + " max(porte) porte, max(empresa_uf) uf, max(empresa_municipio_ibge) ibge,"
                + " count(*) qtd, sum(valor_homologado) valor, max(data_resultado) ultima"
                + " FROM mv_prospeccao" + where
                + " GROUP BY cnpj"
                + " ORDER BY valor DESC NULLS LAST, qtd DESC"
                + " LIMIT :lim OFFSET :off";

        List<EmpresaProspeccaoDTO> itens = npjt.query(sql, p, MAPPER);
        return ProspeccaoPage.of(itens, page, size, totalEmpresas);
    }

    private static void eq(StringBuilder where, MapSqlParameterSource p, String col, String val) {
        eq(where, p, col, val, col);
    }

    private static void eq(StringBuilder where, MapSqlParameterSource p, String col, String val, String param) {
        if (notBlank(val)) {
            where.append(" AND ").append(col).append(" = :").append(param).append(' ');
            p.addValue(param, val.trim());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static final RowMapper<EmpresaProspeccaoDTO> MAPPER = (rs, n) -> new EmpresaProspeccaoDTO(
            rs.getString("cnpj"),
            rs.getString("razao"),
            rs.getString("fantasia"),
            rs.getString("cnae"),
            rs.getString("porte"),
            rs.getString("uf"),
            rs.getString("ibge"),
            rs.getInt("qtd"),
            rs.getBigDecimal("valor"),
            rs.getObject("ultima", OffsetDateTime.class)
    );
}
