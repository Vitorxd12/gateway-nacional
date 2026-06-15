package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Propriedades tipadas do módulo de Inteligência de Licitações, mapeadas de
 * {@code gateway.licitacoes.inteligencia.*} no application.yml.
 *
 * <p>Espelha o contrato self-host do SIGTAP: a flag {@code enabled} controla
 * TODO o subsistema. Quando false, nenhum bean de datasource/Hikari/Jdbc é
 * instanciado, nenhuma conexão Postgres é aberta e o boot do gateway permanece
 * intocado — custo zero para quem não consome o cruzamento Licitações↔Empresas.</p>
 *
 * <p>Diferente do SIGTAP (SQLite single-writer, sem pool), este módulo aponta
 * para um Postgres analítico DEDICADO e usa HikariCP. O pool é proposital­mente
 * enxuto: com Virtual Threads (Loom, Spring Boot 4) a concorrência HTTP não
 * exige pool grande — o pool é um portão para a capacidade do banco, não para o
 * número de requests em voo.</p>
 */
@ConfigurationProperties(prefix = "gateway.licitacoes.inteligencia")
public record IntelProperties(
        boolean enabled,
        Datasource datasource,
        Ingestao ingestao,
        Seed seed,
        Enriquecimento enriquecimento,
        Prospeccao prospeccao
) {

    public record Datasource(
            String url,
            String username,
            String password,
            int poolMaxSize,
            int poolMinIdle,
            long connectionTimeoutMs
    ) {
    }

    public record Ingestao(
            String cron,
            int janelaDias,
            int rateLimitRps,
            /**
             * Códigos de modalidade do PNCP varridos pelo ETL. O endpoint
             * {@code /contratacoes/publicacao} exige modalidade — iteramos a
             * lista. Defaults cobrem os mais comuns (6=Pregão Eletrônico,
             * 8=Dispensa, 4=Concorrência Eletrônica, 5=Concorrência Presencial).
             */
            List<Integer> modalidadeCodigos
    ) {
    }

    /**
     * Carga dirigida de validação (M3 do plano): popula o banco com uma
     * amostragem de um município específico para conferir na prática o
     * enriquecimento (CNAE + IBGE). OPT-IN via {@code seed.enabled=true};
     * roda uma vez no startup pelo {@code AracajuSeedRunner}.
     */
    public record Seed(
            boolean enabled,
            String municipioIbge,
            int janelaDias
    ) {
    }

    /**
     * Enriquecimento ASSÍNCRONO (M2.5). A ingestão grava apenas o stub da empresa
     * (CNPJ + razão); este worker preenche CNAE/IBGE em background, em lotes com
     * delay calibrado, respeitando o rate-limit dos providers de CNPJ (evita o
     * bloqueio por rajada visto na ingestão inline).
     */
    public record Enriquecimento(
            boolean enabled,
            String cron,
            int batchSize,
            long delayMs
    ) {
    }

    /**
     * Materialized view de prospecção: estratégia de refresh do read-model que
     * serve as consultas do CRM ({@code mv_prospeccao}).
     */
    public record Prospeccao(
            String refreshCron
    ) {
    }
}
