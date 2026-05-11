package br.com.cernebr.gateway_nacional.operacional.cptec.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Condição meteorológica corrente (METAR) — formato comum a
 * {@code /clima/capital} e {@code /clima/aeroporto/{icao}}.
 *
 * <p>Os campos espelham o vocabulário publicado pelo CPTEC/INPE em
 * {@code servicos.cptec.inpe.br/XML/#metar}, com renomeações para o
 * snake-case do contrato público (mesma forma usada pela BrasilAPI).</p>
 */
@Schema(name = "CondicaoAtualResponse",
        description = "Snapshot METAR atual para uma capital ou aeroporto brasileiro.")
public record CondicaoAtualResponse(
        @Schema(description = "Código ICAO da estação", example = "SBSP")
        String codigoIcao,

        @Schema(description = "Data/hora da última atualização do boletim (ISO local)", example = "2026-05-10T13:00")
        String atualizadoEm,

        @Schema(description = "Pressão atmosférica em hPa", example = "1014")
        String pressaoAtmosferica,

        @Schema(description = "Velocidade do vento em km/h", example = "12")
        String vento,

        @Schema(description = "Direção do vento (graus a partir do norte)", example = "180")
        String direcaoVento,

        @Schema(description = "Umidade relativa do ar em %", example = "65")
        String umidade,

        @Schema(description = "Condição (sigla CPTEC, ex.: ec, pp, pn — ver constants/cptec)", example = "ec")
        String condicao,

        @Schema(description = "Condição traduzida (texto livre)", example = "Encoberto com Chuvas Isoladas")
        String condicaoDesc,

        @Schema(description = "Temperatura em °C", example = "22")
        String temp
) {
}
