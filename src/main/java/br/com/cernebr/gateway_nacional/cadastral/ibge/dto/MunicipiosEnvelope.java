package br.com.cernebr.gateway_nacional.cadastral.ibge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope interno usado pelo cache Redis para transportar a lista de
 * municípios com marcador de tipo na raiz do JSON. Mesmo padrão do
 * {@code CambioEnvelope} — necessário porque o {@code CachedEntry} do RAC
 * não cacheia {@code List<>} diretamente (gap do default-typing tratado
 * pelo {@code ResilientGenericJacksonSerializer}).
 *
 * <p>Não é exposto via OpenAPI ({@code hidden = true}); o controller
 * desembrulha pra retornar a lista crua, mantendo paridade com o shape
 * da BrasilAPI.</p>
 */
@Schema(name = "MunicipiosEnvelope", hidden = true)
public record MunicipiosEnvelope(List<MunicipioResponse> municipios) {
}
