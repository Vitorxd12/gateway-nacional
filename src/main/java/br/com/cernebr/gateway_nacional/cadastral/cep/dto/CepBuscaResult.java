package br.com.cernebr.gateway_nacional.cadastral.cep.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Payload retornado pelos endpoints de busca textual e geocodificação reversa.
 *
 * <p>Encapsula uma lista de candidatos porque tanto o ViaCEP (busca por logradouro)
 * quanto o Nominatim (reverse) podem retornar mais de um resultado para o mesmo ponto
 * ou logradouro. O cliente deve inspecionar o campo {@code total} e filtrar os
 * candidatos conforme sua regra de negócio (ex.: exibir dropdown no mapa).</p>
 */
@Schema(
        name = "CepBuscaResult",
        description = "Lista de endereços encontrados em uma busca textual ou geocodificação reversa."
)
public record CepBuscaResult(

        @Schema(description = "Total de candidatos retornados.", example = "3")
        int total,

        @Schema(description = "Lista de endereços candidatos, cada um com CEP e campos de localização.")
        List<CepResponse> candidatos
) {
    public static CepBuscaResult of(List<CepResponse> list) {
        return new CepBuscaResult(list.size(), list);
    }
}
