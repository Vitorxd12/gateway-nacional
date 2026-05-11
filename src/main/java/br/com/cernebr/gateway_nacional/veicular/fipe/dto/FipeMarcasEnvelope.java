package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope interno do cache Redis para a lista de marcas. Mesma motivação
 * do {@code CambioEnvelope} — {@code GenericJackson2JsonRedisSerializer}
 * com default-typing não emite type-id em raiz {@code List<>}, gerando
 * cache miss permanente (tratado pelo {@code ResilientGenericJacksonSerializer}).
 * Hidden no OpenAPI; o controller desembrulha pra retornar a lista crua.
 */
@Schema(name = "FipeMarcasEnvelope", hidden = true)
public record FipeMarcasEnvelope(List<FipeMarcaResponse> marcas) {
}
