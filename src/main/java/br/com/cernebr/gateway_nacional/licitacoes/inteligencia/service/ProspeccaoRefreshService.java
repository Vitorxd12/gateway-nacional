package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Refresh do read-model {@code mv_prospeccao} (M3). Estratégia: refresh
 * incremental agendado (cron) + chamada explícita pós-ETL/backfill, para que o
 * CRM sempre leia a MV — nunca os joins crus.
 *
 * <p>Usa {@code REFRESH ... CONCURRENTLY} (não trava leituras do CRM durante o
 * refresh; exige o índice único {@code ux_mvprosp}). Faz fallback para refresh
 * bloqueante caso o concorrente não seja possível (ex.: MV ainda nunca populada).</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class ProspeccaoRefreshService {

    private final JdbcTemplate jdbc;

    public ProspeccaoRefreshService(@Qualifier("licIntelJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${gateway.licitacoes.inteligencia.prospeccao.refresh-cron}")
    public void refrescarAgendado() {
        refrescar();
    }

    /** Refresh idempotente da MV. Seguro chamar a qualquer momento. */
    public void refrescar() {
        try {
            jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_prospeccao");
            log.info("[LIC-INTEL] mv_prospeccao atualizada (CONCURRENTLY).");
        } catch (RuntimeException ex) {
            log.warn("[LIC-INTEL] Refresh CONCURRENTLY falhou ({}); tentando refresh bloqueante.", ex.toString());
            jdbc.execute("REFRESH MATERIALIZED VIEW mv_prospeccao");
            log.info("[LIC-INTEL] mv_prospeccao atualizada (bloqueante).");
        }
    }
}
