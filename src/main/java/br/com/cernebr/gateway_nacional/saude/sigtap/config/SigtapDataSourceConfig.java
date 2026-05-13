package br.com.cernebr.gateway_nacional.saude.sigtap.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.JDBC;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuração da camada de dados SIGTAP — SQLite embarcado.
 *
 * <p><b>Gating absoluto pelo flag do cron:</b> a anotação
 * {@link ConditionalOnProperty} garante que NENHUM bean deste módulo é
 * instanciado quando {@code gateway.saude.sigtap.cron.enabled=false}.
 * Sem DataSource, sem JdbcTemplate, sem transação. Toda a cadeia de
 * serviços/controllers do SIGTAP injeta {@code Optional<JdbcTemplate>}
 * e devolve 503 "SIGTAP desativado" quando ausente — o boot do gateway
 * fica zero-cost para self-hosts que não consomem o módulo.</p>
 *
 * <p><b>Lazy file creation:</b> usamos {@link SimpleDriverDataSource}
 * (sem pool, sem validação eager) — o arquivo {@code data/sigtap.db}
 * só é criado quando a primeira query/DDL roda, ou seja, quando o ETL
 * dispara. SQLite é single-writer; pool não traz benefício e Hikari
 * estaria pagando overhead à toa.</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapDataSourceConfig {

    @Bean(name = "sigtapDataSource")
    public DataSource sigtapDataSource(SigtapProperties props) {
        String path = props.database().path();
        Path absolute = Path.of(path).toAbsolutePath();
        absolute.getParent().toFile().mkdirs();

        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new JDBC());
        ds.setUrl("jdbc:sqlite:" + absolute);

        Properties connProps = new Properties();
        // Foreign keys são opt-in no SQLite.
        connProps.setProperty("foreign_keys", "true");
        // WAL: leitores concorrentes não bloqueiam o writer do ETL.
        connProps.setProperty("journal_mode", "WAL");
        // Reduz fsync pressure no boot do ETL sem perder durabilidade D+1.
        connProps.setProperty("synchronous", "NORMAL");
        ds.setConnectionProperties(connProps);

        log.info("[SIGTAP] SQLite DataSource registrado (lazy). Arquivo: {}", absolute);
        return ds;
    }

    @Bean(name = "sigtapJdbcTemplate")
    public JdbcTemplate sigtapJdbcTemplate(DataSource sigtapDataSource) {
        return new JdbcTemplate(sigtapDataSource);
    }

    @Bean(name = "sigtapTxManager")
    public PlatformTransactionManager sigtapTxManager(DataSource sigtapDataSource) {
        return new DataSourceTransactionManager(sigtapDataSource);
    }

    @Bean(name = "sigtapTxTemplate")
    public TransactionTemplate sigtapTxTemplate(PlatformTransactionManager sigtapTxManager) {
        return new TransactionTemplate(sigtapTxManager);
    }
}
