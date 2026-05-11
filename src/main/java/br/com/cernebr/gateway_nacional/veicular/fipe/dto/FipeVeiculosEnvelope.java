package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope interno do cache Redis para a lista de modelos de uma marca.
 * Mesmo motivo do {@link FipeMarcasEnvelope}.
 */
@Schema(name = "FipeVeiculosEnvelope", hidden = true)
public record FipeVeiculosEnvelope(List<FipeVeiculoResponse> veiculos) {
}
