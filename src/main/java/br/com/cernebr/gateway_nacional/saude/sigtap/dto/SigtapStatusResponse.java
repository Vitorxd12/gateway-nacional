package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(
        name = "SigtapStatusResponse",
        description = "Visão consolidada da saúde e histórico de sincronização do motor SIGTAP."
)
public record SigtapStatusResponse(
        @Schema(description = "Configurações atuais do agendador e flags de sistema")
        ConfiguracaoDTO configuracao,

        @Schema(description = "Detalhes da competência que está servindo a API no momento")
        BaseAtivaDTO baseAtiva,

        @Schema(description = "Resumo da última tentativa de atualização (sucesso ou falha)")
        UltimaExecucaoDTO ultimaExecucao,

        @Schema(description = "Histórico dos últimos 5 datasets processados (incluindo falhas e staging)")
        List<HistoricoDatasetDTO> historicoRecente
) {
    public record ConfiguracaoDTO(
            @Schema(description = "Indica se o motor está autorizado a rodar", example = "true")
            boolean cronHabilitado,

            @Schema(description = "Agendamento configurado (Spring Cron)", example = "0 0 3 * * *")
            String cronExpressao,

            @Schema(description = "Diretório de trabalho para downloads e extração")
            String workDir
    ) {}

    public record BaseAtivaDTO(
            @Schema(description = "Mês de referência (AAAAMM)", example = "202605")
            String competencia,

            @Schema(description = "Versão incremental publicada pelo DataSUS", example = "1")
            String revisao,

            @Schema(description = "Data/hora em que esta base foi ativada localmente")
            OffsetDateTime promovidaEm,

            @Schema(description = "Contagem total de procedimentos indexados", example = "5542")
            int totalProcedimentos,

            @Schema(description = "URL ou caminho de origem dos dados")
            String fonte
    ) {}

    public record UltimaExecucaoDTO(
            @Schema(description = "Início da última tentativa registrada")
            OffsetDateTime iniciadaEm,

            @Schema(description = "Status final (ACTIVE, FAILED, STAGING)")
            String status,

            @Schema(description = "Mensagem detalhada em caso de falha ou observações", example = "FTP retrieveFile falhou...")
            String notas,

            @Schema(description = "Indica se há um processo de ingestão rodando agora")
            boolean emAndamento
    ) {}

    public record HistoricoDatasetDTO(
            Long id,
            String competencia,
            String revisao,
            String status,
            OffsetDateTime iniciadoEm,
            String notas
    ) {}
}
