package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope interno do cache Redis para a lista de tabelas de referência.
 * Mesmo motivo do {@link FipeMarcasEnvelope}.
 */
@Schema(name = "FipeTabelasEnvelope", hidden = true)
public record FipeTabelasEnvelope(List<FipeTabelaReferenciaResponse> tabelas) {
}
