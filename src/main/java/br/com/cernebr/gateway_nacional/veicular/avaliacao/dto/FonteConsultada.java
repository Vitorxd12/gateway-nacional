package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Volumetria de uma fonte (marketplace) participante do fan-out de
 * raspagem. Expõe ao consumidor da API <i>quantos</i> anúncios cada player
 * contribuiu para a amostra agregada — fundamental para auditoria e para
 * permitir que o front-end (Inspetor Dual-Tab) explique a composição do
 * Market Score.
 *
 * <p>{@code statusColeta} carrega o veredito macro: {@code OK} quando
 * houve anúncios retornados, {@code VAZIO} quando o scraper rodou mas
 * não trouxe preços (seletor obsoleto / 0 anúncios na busca regional),
 * {@code FALHA} quando o scraper lançou exceção ou o Circuit Breaker
 * estava aberto, {@code TIMEOUT} quando estourou o hard-timeout do
 * orquestrador.</p>
 */
@Schema(name = "FonteConsultada",
        description = "Volumetria por fonte (marketplace) no fan-out de raspagem.")
public record FonteConsultada(
        @Schema(description = "Nome estável do marketplace (provider name do scraper).",
                example = "MercadoLivre")
        String fonte,

        @Schema(description = "Status macro da coleta nesta fonte.",
                example = "OK",
                allowableValues = {"OK", "VAZIO", "FALHA", "TIMEOUT"})
        String statusColeta,

        @Schema(description = "Quantidade de anúncios efetivamente raspados nesta fonte (antes do filtro Anti-Outlier).",
                example = "23")
        int anunciosColetados,

        @Schema(description = "Quantidade de outliers descartados pertencentes a esta fonte (cruzando a amostra agregada).",
                example = "2")
        int outliersDescartados,

        @Schema(description = "URL exata consultada nesta requisição (já regionalizada quando aplicável).",
                example = "https://carros.mercadolivre.com.br/volkswagen/gol/ano-2015/")
        String linkReferencia
) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_VAZIO = "VAZIO";
    public static final String STATUS_FALHA = "FALHA";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
}
