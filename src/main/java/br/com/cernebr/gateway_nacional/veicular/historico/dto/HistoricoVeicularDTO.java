package br.com.cernebr.gateway_nacional.veicular.historico.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Consolidated free-tier vehicle history — union of leilão (auction) and
 * sinistro (insurance claim / wreck) indicators, plus the surviving sources.
 *
 * <p>This is a <b>fail-soft</b> envelope: when a scraper crashes (network,
 * Cloudflare, selector drift), the orchestrator drops it from
 * {@link #fontesConsultadas()} and emits the response anyway. Callers that
 * need to know the audit perimeter read the list and decide whether the
 * coverage is acceptable for their use case (e.g., fraud workflow may
 * require ≥2 sources to mark a placa "limpa").</p>
 *
 * <p>The two boolean flags are derived from the union of evidence collected
 * across surviving sources — any single match flips the flag to {@code true}.
 * {@link #detalhesLeilao()} is filled with a one-line excerpt that the
 * scraper extracted (e.g., {@code "Leilão Copart 12/03/2024 - Sinistro/Salvado"})
 * so the operator can audit without re-querying the upstream.</p>
 *
 * @param placa                normalized uppercase placa, without hyphen;
 * @param indicioLeilao        true when at least one source reported leilão;
 * @param indicioSinistro      true when at least one source reported sinistro;
 * @param detalhesLeilao       human-readable evidence trail for leilão hits
 *                             (null when no leilão evidence found);
 * @param riscoConsolidado     {@link RiscoConsolidado} bucket derived from
 *                             the two booleans;
 * @param fontesConsultadas    stable identifiers of the sources that produced
 *                             usable data — failed sources are NOT listed.
 */
@Schema(
        name = "HistoricoVeicularDTO",
        description = "Consolidação gratuita do histórico veicular (leilão + sinistro) com risco agregado e auditoria de fontes."
)
public record HistoricoVeicularDTO(

        @Schema(description = "Placa normalizada em uppercase, sem hífen.", example = "ABC1D23")
        String placa,

        @Schema(description = "true quando ao menos uma fonte sobrevivente reportou registro de leilão.", example = "true")
        boolean indicioLeilao,

        @Schema(description = "true quando ao menos uma fonte sobrevivente reportou indício de sinistro/salvado.", example = "true")
        boolean indicioSinistro,

        @Schema(description = "Trilho de evidência humanamente legível extraído da página de leilão (null quando sem indício).",
                example = "Leilão Copart - 12/03/2024 - Sinistro/Salvado - LEILOEIRO XYZ", nullable = true)
        String detalhesLeilao,

        @Schema(description = "Risco consolidado derivado da união dos indícios.")
        RiscoConsolidado riscoConsolidado,

        @Schema(description = "Identificadores estáveis das fontes que produziram dados utilizáveis. Fontes que falharam não aparecem nesta lista.",
                example = "[\"LeilaoFree\", \"ConsultarPlaca\"]")
        List<String> fontesConsultadas
) {
}
