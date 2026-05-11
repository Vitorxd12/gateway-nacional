package br.com.cernebr.gateway_nacional.operacional.cptec.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Previsão de ondas para uma cidade litorânea — janela típica de 1–6 dias.
 *
 * <p>O CPTEC retorna o conjunto de medidas hora-a-hora. O Gateway agrupa
 * por dia para reduzir o payload e facilitar o consumo em dashboards
 * portuários/logísticos.</p>
 */
@Schema(name = "OndasResponse",
        description = "Previsão de ondas e vento marítimo agregada por dia para cidades litorâneas.")
public record OndasResponse(
        @Schema(description = "Nome da cidade", example = "Itaqui")
        String cidade,

        @Schema(description = "Sigla da UF", example = "MA")
        String estado,

        @Schema(description = "Data/hora da última atualização do boletim", example = "10/05/2026 13:00")
        String atualizadoEm,

        @Schema(description = "Vetor de dias com previsões de onda agrupadas por hora")
        List<DiaOndas> ondas
) {

    public record DiaOndas(
            @Schema(description = "Data ISO yyyy-MM-dd", example = "2026-05-11")
            String data,

            @Schema(description = "Medições hora-a-hora do dia")
            List<MedidaOndas> dadosOndas
    ) {
    }

    public record MedidaOndas(
            @Schema(description = "Hora local com timezone", example = "06:00-03")
            String hora,
            @Schema(description = "Vento (km/h)", example = "12")
            String vento,
            @Schema(description = "Direção do vento (sigla cardinal)", example = "NE")
            String direcaoVento,
            @Schema(description = "Direção do vento descrita", example = "Nordeste")
            String direcaoVentoDesc,
            @Schema(description = "Altura da onda em metros", example = "1.2")
            String alturaOnda,
            @Schema(description = "Direção da onda (sigla cardinal)", example = "S")
            String direcaoOnda,
            @Schema(description = "Direção da onda descrita", example = "Sul")
            String direcaoOndaDesc,
            @Schema(description = "Agitação do mar (Fraco/Moderado/Forte)", example = "Moderado")
            String agitacao
    ) {
    }
}
