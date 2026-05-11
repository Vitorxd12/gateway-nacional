package br.com.cernebr.gateway_nacional.saude.tuss.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Página de resultados para a busca/listagem TUSS. Embrulha a lista em um
 * record concreto para escapar da lacuna de default-typing do
 * {@code GenericJackson2JsonRedisSerializer} (ver {@code CacheConfig#redisValueSerializer}).
 */
@Schema(name = "TussPageResponse",
        description = "Página de resultados TUSS com metadados de paginação.")
public record TussPageResponse(
        @Schema(description = "Total de registros que casam com o filtro antes da paginação.", example = "5234")
        int total,

        @Schema(description = "Tamanho da página solicitada (null = página inteira).", example = "50")
        Integer limit,

        @Schema(description = "Deslocamento aplicado.", example = "0")
        int offset,

        @Schema(description = "Provedor que respondeu — útil para auditoria/observabilidade.",
                example = "BrasilAPI-TUSS")
        String fonte,

        @Schema(description = "Lista de códigos TUSS na ordem que o upstream retornou.")
        List<TussCodigoResponse> items
) {
}
