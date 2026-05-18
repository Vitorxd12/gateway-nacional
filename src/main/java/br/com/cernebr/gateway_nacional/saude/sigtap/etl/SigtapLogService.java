package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Central de observabilidade para o motor SIGTAP.
 * Mantém um buffer circular de logs em memória para exibição em tempo real.
 */
@Slf4j
@Service
public class SigtapLogService {

    private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
    private final Queue<String> currentRunLogs = new ConcurrentLinkedQueue<>();

    public void log(String msg) {
        log.info(msg);
        String timestamped = "[" + LocalTime.now().withNano(0) + "] " + msg;
        buffer.offer(timestamped);
        currentRunLogs.offer(timestamped);
        
        // Mantém apenas os últimos 100 logs para o status dashboard
        while (buffer.size() > 100) {
            buffer.poll();
        }

        // Mantém todos os logs da execução atual com um limite seguro e generoso (5000 logs)
        while (currentRunLogs.size() > 5000) {
            currentRunLogs.poll();
        }
    }

    public List<String> getLogs() {
        return new ArrayList<>(buffer);
    }

    public List<String> getCurrentRunLogs() {
        return new ArrayList<>(currentRunLogs);
    }

    public void clear() {
        buffer.clear();
        currentRunLogs.clear();
    }
}
