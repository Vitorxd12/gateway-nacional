package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bootstrap programático do schema analítico, idempotente e sem migrations —
 * mesmo idioma do SIGTAP ({@code CREATE TABLE IF NOT EXISTS} via JdbcTemplate),
 * adaptado para a sintaxe PostgreSQL. Decisão de projeto: o time mantém o padrão
 * "bootstrap-em-código" e NÃO adota Flyway.
 *
 * <p>Roda uma única vez no {@code @PostConstruct} — e, por ser
 * {@link ConditionalOnProperty conditional} ao mesmo flag do
 * {@code IntelDataSourceConfig}, só existe quando o módulo está ligado.</p>
 *
 * <p>Centralizamos TODO o DDL aqui (em vez de espalhar pelos repositórios) para
 * que o schema seja legível num único lugar e os repositórios fiquem só com CRUD.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class IntelSchemaBootstrap {

    private final JdbcTemplate jdbc;

    public IntelSchemaBootstrap(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureSchema() {
        // pg_trgm habilita índices GIN para busca fuzzy por razão social / objeto.
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");

        // ── EMPRESA: mestre de fornecedor, enriquecido via cadastral/cnpj ──────
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS empresa (
                    cnpj              CHAR(14)    PRIMARY KEY,
                    razao_social      TEXT        NOT NULL,
                    nome_fantasia     TEXT,
                    cnae_principal    CHAR(7),
                    porte             TEXT,
                    natureza_juridica TEXT,
                    uf                CHAR(2),
                    municipio_nome    TEXT,
                    municipio_ibge    CHAR(7),
                    situacao          TEXT,
                    enriquecido_em    TIMESTAMPTZ,
                    atualizado_em     TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_emp_cnae ON empresa (cnae_principal)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_emp_mun  ON empresa (municipio_ibge)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_emp_uf   ON empresa (uf)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_emp_razao_trgm "
                + "ON empresa USING gin (razao_social gin_trgm_ops)");

        // ── EMPRESA_CNAE: ramo de atuação é N:N (CNAEs secundários) ────────────
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS empresa_cnae (
                    cnpj      CHAR(14) NOT NULL REFERENCES empresa (cnpj) ON DELETE CASCADE,
                    cnae      CHAR(7)  NOT NULL,
                    principal BOOLEAN  NOT NULL DEFAULT false,
                    PRIMARY KEY (cnpj, cnae)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_empcnae_cnae ON empresa_cnae (cnae, cnpj)");

        // ── LICITAÇÃO: snapshot persistido (desacopla do portal vivo) ──────────
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS licitacao (
                    id                   BIGSERIAL    PRIMARY KEY,
                    portal               TEXT         NOT NULL,
                    identificador        TEXT         NOT NULL,
                    numero               TEXT,
                    objeto               TEXT,
                    modalidade           TEXT,
                    setor                TEXT,
                    orgao_cnpj           CHAR(14),
                    orgao_nome           TEXT,
                    orgao_uf             CHAR(2),
                    orgao_municipio_nome TEXT,
                    orgao_municipio_ibge CHAR(7),
                    valor_estimado       NUMERIC(18,2),
                    valor_homologado     NUMERIC(18,2),
                    data_abertura        TIMESTAMPTZ,
                    data_resultado       TIMESTAMPTZ,
                    ano                  SMALLINT     NOT NULL,
                    situacao             TEXT,
                    fonte                TEXT         NOT NULL DEFAULT 'pncp',
                    ingerido_em          TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    UNIQUE (portal, identificador)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_lic_setor_mun_data "
                + "ON licitacao (setor, orgao_municipio_ibge, data_resultado DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_lic_uf_modalidade ON licitacao (orgao_uf, modalidade)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_lic_objeto_trgm "
                + "ON licitacao USING gin (objeto gin_trgm_ops)");

        // ── PARTICIPAÇÃO: o N:N — coração do cruzamento ────────────────────────
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS participacao (
                    id               BIGSERIAL    PRIMARY KEY,
                    licitacao_id     BIGINT       NOT NULL REFERENCES licitacao (id) ON DELETE CASCADE,
                    empresa_cnpj     CHAR(14)     NOT NULL REFERENCES empresa (cnpj),
                    papel            TEXT         NOT NULL,
                    item_sequencial  INTEGER,
                    classificacao    INTEGER,
                    valor_proposta   NUMERIC(18,2),
                    valor_homologado NUMERIC(18,2),
                    data_resultado   TIMESTAMPTZ,
                    ano              SMALLINT     NOT NULL,
                    fonte            TEXT         NOT NULL DEFAULT 'pncp',
                    ingerido_em      TIMESTAMPTZ  NOT NULL DEFAULT now()
                )
                """);
        // Idempotência do upsert. item_sequencial é nullable (null = certame
        // inteiro); COALESCE(-1) evita o "NULLs distintos" do índice único, que
        // deixaria duplicar a participação no nível do certame.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_part_idem "
                + "ON participacao (licitacao_id, empresa_cnpj, papel, COALESCE(item_sequencial, -1))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_part_empresa "
                + "ON participacao (empresa_cnpj, data_resultado DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_part_licitacao ON participacao (licitacao_id)");

        // ── CURSOR de ETL (retomada incremental por portal) ────────────────────
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ingestao_cursor (
                    portal           TEXT PRIMARY KEY,
                    ultima_data_proc TIMESTAMPTZ,
                    ultima_execucao  TIMESTAMPTZ
                )
                """);

        // ── READ-MODEL: materialized view de prospecção (M3) ───────────────────
        // Achata participacao⨝licitacao⨝empresa para servir as consultas do CRM
        // num único índice, sem joins em runtime. Refresh CONCURRENTLY (exige o
        // índice único ux_mvprosp) é disparado pelo ProspeccaoRefreshService.
        jdbc.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS mv_prospeccao AS
                SELECT p.id                    AS participacao_id,
                       p.papel,
                       p.data_resultado,
                       p.valor_homologado,
                       p.item_sequencial,
                       l.id                    AS licitacao_id,
                       l.portal,
                       l.identificador,
                       l.numero,
                       l.objeto,
                       l.modalidade,
                       l.setor,
                       l.orgao_uf              AS edital_uf,
                       l.orgao_municipio_ibge  AS edital_municipio_ibge,
                       l.orgao_municipio_nome  AS edital_municipio_nome,
                       e.cnpj,
                       e.razao_social,
                       e.nome_fantasia,
                       e.cnae_principal,
                       e.porte,
                       e.uf                    AS empresa_uf,
                       e.municipio_ibge        AS empresa_municipio_ibge,
                       e.municipio_nome        AS empresa_municipio_nome
                FROM participacao p
                JOIN licitacao l ON l.id = p.licitacao_id
                JOIN empresa   e ON e.cnpj = p.empresa_cnpj
                WITH DATA
                """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_mvprosp ON mv_prospeccao (participacao_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_mvprosp_setor "
                + "ON mv_prospeccao (setor, edital_municipio_ibge, data_resultado DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_mvprosp_empcnae "
                + "ON mv_prospeccao (cnae_principal, empresa_municipio_ibge)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_mvprosp_empuf ON mv_prospeccao (empresa_uf)");

        log.info("[LIC-INTEL] Schema analítico verificado/provisionado (Postgres, sem migrations).");
    }
}
