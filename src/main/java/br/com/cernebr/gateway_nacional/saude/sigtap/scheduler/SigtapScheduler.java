package br.com.cernebr.gateway_nacional.saude.sigtap.scheduler;

import br.com.cernebr.gateway_nacional.saude.sigtap.config.SigtapProperties;
import br.com.cernebr.gateway_nacional.saude.sigtap.etl.SigtapEtlException;
import br.com.cernebr.gateway_nacional.saude.sigtap.etl.SigtapEtlService;
import br.com.cernebr.gateway_nacional.saude.sigtap.jdbc.SigtapJdbc;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart Cron do SIGTAP — agendamento na madrugada (03h por default) com
 * exponential backoff intra-execução.
 *
 * <p><b>Smart skip:</b> antes de tocar a rede, checa via
 * {@link SigtapJdbc#hasActiveForCompetencia(String)} se a competência
 * corrente já está ACTIVE. Isso evita downloads diários redundantes —
 * o DataSUS atualiza a tabela uma única vez por mês, geralmente entre
 * o dia 1 e o dia 5; o job vai rodar todas as madrugadas, mas só faz
 * trabalho útil até promover a competência.</p>
 *
 * <p><b>Exponential backoff:</b> dentro da MESMA execução noturna,
 * se o download falhar, retentamos com delays crescentes
 * ({@code initialDelayMs * multiplier^n}). Cobre instabilidades curtas
 * do FTP DataSUS sem martelar.</p>
 *
 * <p><b>Bootstrap on empty:</b> no startup (após DI estar pronto), se
 * {@code seed-on-empty=true} e não houver ACTIVE algum, carrega o
 * fixture embarcado para garantir que as rotas REST respondam mesmo
 * antes do primeiro download real.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapScheduler {

    private final SigtapEtlService etl;
    private final SigtapJdbc jdbc;
    private final SigtapProperties props;

    private final AtomicBoolean runningNow = new AtomicBoolean(false);

    public SigtapScheduler(SigtapEtlService etl, SigtapJdbc jdbc, SigtapProperties props) {
        this.etl = etl;
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostConstruct
    void bootstrapOnEmpty() {
        if (!props.etl().seedOnEmpty()) return;
        try {
            jdbc.ensureSchema();
            if (jdbc.findActive().isPresent()) {
                log.info("[SIGTAP] Bootstrap: já há dataset ACTIVE — fixture skip.");
                return;
            }
            log.info("[SIGTAP] Bootstrap: nenhum dataset ACTIVE — carregando fixture embarcado.");
            etl.ingerirFixture();
        } catch (Exception ex) {
            log.warn("[SIGTAP] Bootstrap fixture falhou — gateway segue operando sem SIGTAP. cause={}",
                    ex.toString());
        }
    }

    @Scheduled(cron = "${gateway.saude.sigtap.cron.expression}")
    public void rodadaDiaria() {
        if (!runningNow.compareAndSet(false, true)) {
            log.warn("[SIGTAP] Rodada anterior ainda em execução — pulando esta janela.");
            return;
        }
        try {
            log.info("[SIGTAP] Smart cron iniciado em {}", LocalDateTime.now());
            executarComBackoff();
        } finally {
            runningNow.set(false);
        }
    }

    private void executarComBackoff() {
        long delay = props.retry().initialDelayMs();
        double multiplier = props.retry().multiplier();
        int maxAttempts = props.retry().maxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                boolean ok = etl.executar();
                if (ok) {
                    log.info("[SIGTAP] Rodada concluída na tentativa {}/{}", attempt, maxAttempts);
                    return;
                }
            } catch (SigtapEtlException ex) {
                if (attempt == maxAttempts) {
                    log.error("[SIGTAP] Tentativa final {}/{} falhou — desistindo até a próxima janela. cause={}",
                            attempt, maxAttempts, ex.getMessage());
                    return;
                }
                log.warn("[SIGTAP] Tentativa {}/{} falhou ({}). Aguardando {}ms antes do próximo backoff.",
                        attempt, maxAttempts, ex.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[SIGTAP] Backoff interrompido — encerrando rodada.");
                    return;
                }
                delay = (long) (delay * multiplier);
            }
        }
    }
}
