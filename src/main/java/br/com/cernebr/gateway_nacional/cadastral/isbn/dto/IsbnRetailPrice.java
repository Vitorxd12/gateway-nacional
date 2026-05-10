package br.com.cernebr.gateway_nacional.cadastral.isbn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Preço de capa quando o provider o expõe (Google Books, Mercado Editorial).
 * {@code null} para fontes que não publicam preço (CBL, Open Library, BrasilAPI
 * via fontes que não propagaram).
 *
 * <p>{@link BigDecimal} para preservar precisão monetária — evita o
 * arredondamento que {@code Double} introduz em valores como {@code 49.90}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Preço de capa publicado pelo provider")
public record IsbnRetailPrice(
        @Schema(example = "BRL") String currency,
        @Schema(example = "49.90") BigDecimal amount
) {
}
