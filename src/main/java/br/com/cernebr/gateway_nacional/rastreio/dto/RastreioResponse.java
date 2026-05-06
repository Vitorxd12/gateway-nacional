package br.com.cernebr.gateway_nacional.rastreio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Unified tracking payload exposed by the Gateway, regardless of which
 * upstream provider (Link&amp;Track, BrasilAPI, Correios Oficial) actually
 * resolved the lookup.
 *
 * <p>{@code eventos} is ordered <b>most recent first</b> — UI consumers can
 * iterate naturally and clients computing latest-status do so via {@code
 * eventos.get(0)} without re-sorting.</p>
 */
@Schema(name = "RastreioResponse", description = "Histórico de rastreio de uma encomenda em formato unificado pelo Gateway Nacional.")
public record RastreioResponse(
        @Schema(description = "Código de rastreio normalizado em uppercase", example = "LB123456789BR")
        String codigo,

        @Schema(description = "Indica se há evento de entrega registrado na linha do tempo", example = "false")
        boolean isEntregue,

        @Schema(description = "Eventos da linha do tempo, ordenados do mais recente para o mais antigo")
        List<EventoRastreio> eventos
) {
}
