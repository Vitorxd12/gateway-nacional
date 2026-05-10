package br.com.cernebr.gateway_nacional.cadastral.isbn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Dados bibliográficos unificados a partir do ISBN.
 *
 * <p>O shape replica a resposta pública da BrasilAPI ({@code snake_case}
 * em campos compostos via {@link JsonProperty}) — clientes que migram da
 * BrasilAPI para o gateway não precisam ajustar parser. {@code null} em
 * qualquer campo significa que o provider que respondeu não expôs aquela
 * informação; o cliente deve tratar como ausente, não como erro.</p>
 *
 * <p>{@code provider} carrega o nome do provider efetivamente vencedor do
 * hedge — útil para auditoria e para o cliente entender de onde veio o dado
 * (CBL costuma ter mais detalhe local; Google Books tem cobertura ampla;
 * Open Library tende a ter capa).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Dados bibliográficos de uma obra a partir do ISBN")
public record IsbnResponse(
        @Schema(example = "9788532530803") String isbn,
        @Schema(example = "Harry Potter e a Pedra Filosofal") String title,
        @Schema(example = "A história começa") String subtitle,
        @Schema(example = "[\"J. K. Rowling\"]") List<String> authors,
        @Schema(example = "Rocco") String publisher,
        @Schema(example = "Sinopse da obra…") String synopsis,
        IsbnDimensions dimensions,
        @Schema(example = "2017") Integer year,
        @Schema(example = "PHYSICAL", allowableValues = {"PHYSICAL", "DIGITAL"}) String format,
        @JsonProperty("page_count") @Schema(name = "page_count", example = "264") Integer pageCount,
        @Schema(example = "[\"Literatura inglesa\"]") List<String> subjects,
        @Schema(example = "Rio de Janeiro [RJ]") String location,
        @JsonProperty("retail_price") @Schema(name = "retail_price") IsbnRetailPrice retailPrice,
        @JsonProperty("cover_url") @Schema(name = "cover_url",
                example = "https://covers.openlibrary.org/b/isbn/9788532530803-L.jpg") String coverUrl,
        @Schema(example = "BrasilAPI", description = "Provider efetivamente vencedor") String provider
) {
}
