package br.com.cernebr.gateway_nacional.financeiro.cambio.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope interno usado pelo cache Redis para transportar a coleção de
 * cotações com um marcador de tipo na raiz do JSON.
 *
 * <p>Necessário porque o {@code GenericJackson2JsonRedisSerializer} do Spring
 * Data Redis 4.0.x — combinado com {@code default typing} — não emite o
 * cabeçalho {@code @class} em raízes do tipo array. Sem o marcador, a
 * deserialização de {@code List<Record>} falha silenciosamente (vide
 * {@code ResilientGenericJacksonSerializer} em {@code CacheConfig}), o que na
 * prática converte cada {@code @Cacheable} em um cache miss permanente. Ao
 * envelopar a lista em um record concreto, a raiz do JSON vira um objeto e o
 * default-typing escreve o {@code @class} corretamente — round-trip perfeito.</p>
 */
@Schema(name = "CambioEnvelope", hidden = true)
public record CambioEnvelope(List<CambioResponse> cotacoes) {
}
