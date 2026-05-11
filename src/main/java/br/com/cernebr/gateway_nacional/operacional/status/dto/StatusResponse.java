package br.com.cernebr.gateway_nacional.operacional.status.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot consolidado da saúde do gateway — formato amigável para status
 * pages públicos (uptime monitors, dashboards estilo {@code status.cernebr.com})
 * sem precisar parsear {@code /actuator/health} (que mistura JVM/Redis/providers
 * num único blob e em produção fica autenticado).
 *
 * <p><b>Três níveis de granularidade:</b></p>
 * <ol>
 *   <li>{@link #gateway} — saúde do processo (uptime, Redis, status agregado);</li>
 *   <li>{@link #dominios} — um {@link DominioStatus} por área funcional (cep,
 *       cambio, rastreio, …), calculado a partir das métricas
 *       {@code gateway.provider.requests};</li>
 *   <li>Dentro de cada domínio, lista de {@link ProviderHealth} com Circuit
 *       Breaker state e taxa de falha por provider individual.</li>
 * </ol>
 *
 * <p><b>Sem auth.</b> Sem cache de servidor (status precisa ser real-time);
 * o controller emite {@code Cache-Control: no-store} para impedir que um CDN
 * congele o snapshot e mascare incidentes em curso.</p>
 */
@Schema(name = "StatusResponse",
        description = "Snapshot consolidado da saúde do gateway e dos providers upstream, agrupado por domínio funcional.")
public record StatusResponse(
        @Schema(description = "Versão do gateway em execução", example = "0.0.1-SNAPSHOT")
        String versao,

        @Schema(description = "Profile Spring ativo no processo", example = "default")
        String ambiente,

        @Schema(description = "Timestamp ISO-8601 em que este snapshot foi gerado.", example = "2026-05-11T18:30:00Z")
        Instant geradoEm,

        @Schema(description = "Saúde do processo (uptime, Redis, status agregado)")
        GatewayHealth gateway,

        @Schema(description = "Saúde por domínio funcional — ordenado alfabeticamente.")
        List<DominioStatus> dominios
) {

    @Schema(name = "StatusResponse.GatewayHealth")
    public record GatewayHealth(
            @Schema(description = "Status agregado: operacional, degradado ou indisponivel.",
                    example = "operacional",
                    allowableValues = {"operacional", "degradado", "indisponivel"})
            String status,

            @Schema(description = "Tempo desde o boot do processo, em formato humano.", example = "3d 4h 12m")
            String uptime,

            @Schema(description = "Saúde do Redis — false desativa o cache mas não derruba o gateway.")
            RedisHealth redis
    ) {
    }

    @Schema(name = "StatusResponse.RedisHealth")
    public record RedisHealth(
            @Schema(description = "Status do Redis", example = "operacional",
                    allowableValues = {"operacional", "indisponivel", "nao_configurado"})
            String status,

            @Schema(description = "Latência do ping em milissegundos (-1 se não respondeu).", example = "2")
            long latenciaMs
    ) {
    }

    @Schema(name = "StatusResponse.DominioStatus")
    public record DominioStatus(
            @Schema(description = "Nome do domínio funcional", example = "cambio")
            String nome,

            @Schema(description = "Status agregado do domínio (pior caso entre os providers ativos).",
                    example = "degradado",
                    allowableValues = {"operacional", "degradado", "indisponivel", "sem_trafego"})
            String status,

            @Schema(description = "Total de requisições agregadas (success + failure + not-found) desde o boot.",
                    example = "12483")
            long requisicoes,

            @Schema(description = "Taxa de sucesso agregada do domínio no intervalo (0..1). Null se requisicoes == 0.",
                    example = "0.94")
            Double taxaSucesso,

            @Schema(description = "Providers que compõem o domínio")
            List<ProviderHealth> providers
    ) {
    }

    @Schema(name = "StatusResponse.ProviderHealth")
    public record ProviderHealth(
            @Schema(description = "Nome canônico do provider (lower-case, igual à tag de métrica).",
                    example = "bcb-olinda-ptax")
            String nome,

            @Schema(description = "Status do provider derivado de CB + taxa de falha.",
                    example = "indisponivel",
                    allowableValues = {"operacional", "degradado", "indisponivel", "sem_trafego"})
            String status,

            @Schema(description = "Total de requisições registradas (counter cumulativo).", example = "843")
            long requisicoes,

            @Schema(description = "Taxa de sucesso (0..1). Null quando requisicoes == 0.", example = "0.21")
            Double taxaSucesso,

            @Schema(description = "Latência média em ms desde o boot. Null sem amostras.", example = "1217")
            Long latenciaMediaMs,

            @Schema(description = "Estado do Circuit Breaker Resilience4j associado ao provider — null quando o mapeamento não é encontrado.",
                    example = "OPEN",
                    allowableValues = {"CLOSED", "OPEN", "HALF_OPEN", "DISABLED", "FORCED_OPEN"})
            String circuitBreaker
    ) {
    }
}
