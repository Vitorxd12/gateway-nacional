package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Camada de dados da Inteligência de Licitações — Postgres analítico DEDICADO.
 *
 * <p><b>Gating absoluto pelo flag:</b> {@link ConditionalOnProperty} garante que
 * NENHUM bean deste módulo é instanciado quando
 * {@code gateway.licitacoes.inteligencia.enabled=false}. Sem DataSource, sem pool
 * Hikari, sem JdbcTemplate, sem transação — o boot do gateway fica zero-cost para
 * self-hosts que não usam o cruzamento Licitações↔Empresas. Idêntico ao gating do
 * SIGTAP ({@code SigtapDataSourceConfig}).</p>
 *
 * <p><b>Por que HikariCP (e não {@code SimpleDriverDataSource} como o SIGTAP):</b>
 * o SIGTAP fala com um SQLite single-writer local, onde pool é overhead inútil.
 * Aqui a fonte é um Postgres remoto multi-conexão consumido por um ETL em lote e
 * por queries analíticas — pool gerenciado é obrigatório.</p>
 *
 * <p><b>Dimensionamento para Virtual Threads (Loom):</b> o gateway roda cada
 * request HTTP em virtual thread, então a concorrência da aplicação é altíssima.
 * O pool, porém, é dimensionado pela capacidade do Postgres, não pelo número de
 * requests — por isso {@code pool-max-size} fica enxuto (default 16). Virtual
 * threads bloqueadas em {@code getConnection()} apenas estacionam (não pinam o
 * carrier), então um pool pequeno apenas serializa o acesso ao banco sem
 * desperdiçar carriers.</p>
 *
 * <p>Beans nomeados ({@code licIntel*}) evitam colisão com qualquer outro
 * DataSource do contexto e tornam a injeção explícita via {@code @Qualifier}.</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class IntelDataSourceConfig {

    @Bean(name = "licIntelDataSource", destroyMethod = "close")
    public DataSource licIntelDataSource(IntelProperties props) {
        IntelProperties.Datasource cfg = props.datasource();

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("lic-intel-pool");
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setJdbcUrl(cfg.url());
        hikari.setUsername(cfg.username());
        hikari.setPassword(cfg.password());
        // Pool enxuto: portão para a capacidade do Postgres, não para o nº de
        // requests. Virtual threads bloqueadas aqui só estacionam.
        hikari.setMaximumPoolSize(cfg.poolMaxSize());
        hikari.setMinimumIdle(cfg.poolMinIdle());
        hikari.setConnectionTimeout(cfg.connectionTimeoutMs());
        // Sanidade de conexão sem custo de query de validação (pgjdbc usa
        // o protocolo nativo de isValid()).
        hikari.setKeepaliveTime(60_000L);

        HikariDataSource ds = new HikariDataSource(hikari);
        log.info("[LIC-INTEL] Postgres DataSource registrado. pool={} max={} url={}",
                hikari.getPoolName(), cfg.poolMaxSize(), cfg.url());
        return ds;
    }

    @Bean(name = "licIntelJdbcTemplate")
    public JdbcTemplate licIntelJdbcTemplate(DataSource licIntelDataSource) {
        return new JdbcTemplate(licIntelDataSource);
    }

    @Bean(name = "licIntelTxManager")
    public PlatformTransactionManager licIntelTxManager(DataSource licIntelDataSource) {
        return new DataSourceTransactionManager(licIntelDataSource);
    }

    @Bean(name = "licIntelTxTemplate")
    public TransactionTemplate licIntelTxTemplate(PlatformTransactionManager licIntelTxManager) {
        return new TransactionTemplate(licIntelTxManager);
    }
}
