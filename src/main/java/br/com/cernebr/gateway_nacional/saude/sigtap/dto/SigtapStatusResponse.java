package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(
        name = "SigtapStatusResponse",
        description = "Saúde operacional do motor SIGTAP — competência ativa, ETL pendente e flags de operação."
)
public record SigtapStatusResponse(
        @Schema(description = "Competência ativa (AAAAMM), ou null se nenhuma carregada", example = "202605")
        String competenciaAtiva,

        @Schema(description = "Data/hora de promoção da competência ativa", example = "2026-05-02T03:14:00Z")
        OffsetDateTime promovidaEm,

        @Schema(description = "Indica se há um dataset em STAGING (ETL em andamento)", example = "false")
        boolean etlEmAndamento,

        @Schema(description = "Valor da flag GATEWAY_SIGTAP_CRON_ENABLED", example = "true")
        boolean cronHabilitado,

        @Schema(description = "Cron expression efetiva, no formato Spring 6 campos", example = "0 0 3 * * *")
        String cronExpressao,

        @Schema(description = "Origem do dataset ativo", example = "TabelaUnificada_202605_v1.zip")
        String fonte
) {
}
