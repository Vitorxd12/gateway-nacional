package br.com.cernebr.gateway_nacional.juridico.sancoes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope interno usado pelo cache Redis para transportar a coleção de
 * sanções com um marcador de tipo na raiz do JSON.
 *
 * <p>Mesma motivação do {@code CambioEnvelope}: o
 * {@code GenericJackson2JsonRedisSerializer} do Spring Data Redis 4.0.x
 * não emite {@code @class} em raízes de array, fazendo com que cada
 * {@code @Cacheable} sobre {@code List<Record>} vire um cache miss
 * permanente. Envelopar a lista em um record concreto resolve o round-trip.</p>
 */
@Schema(name = "SancoesEnvelope", hidden = true)
public record SancoesEnvelope(List<SancaoResponse> sancoes) {
}
