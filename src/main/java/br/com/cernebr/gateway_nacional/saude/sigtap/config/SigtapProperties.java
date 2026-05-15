package br.com.cernebr.gateway_nacional.saude.sigtap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades tipadas do módulo SIGTAP, mapeadas de
 * {@code gateway.saude.sigtap.*} no application.yml.
 *
 * <p>Mantém o contrato com o operador self-host explícito: a flag
 * {@code cron.enabled} controla TODO o subsistema. Quando false,
 * nenhum bean SQLite é instanciado, nenhum arquivo .db é criado, e
 * o boot do gateway permanece intocado.</p>
 */
@ConfigurationProperties(prefix = "gateway.saude.sigtap")
public record SigtapProperties(
        Cron cron,
        Download download,
        Retry retry,
        Etl etl,
        Database database
) {
    public record Cron(boolean enabled, String expression) {
    }

    public record Download(String pacoteUrlTemplate, int timeoutMs) {
    }

    public record Retry(int maxAttempts, long initialDelayMs, double multiplier) {
    }

    public record Etl(String workDir, boolean seedOnEmpty) {
    }

    public record Database(String path) {
    }
}
