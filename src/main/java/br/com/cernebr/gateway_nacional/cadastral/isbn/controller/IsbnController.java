package br.com.cernebr.gateway_nacional.cadastral.isbn.controller;

import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;
import br.com.cernebr.gateway_nacional.cadastral.isbn.exception.IsbnInvalidoException;
import br.com.cernebr.gateway_nacional.cadastral.isbn.service.IsbnService;
import br.com.cernebr.gateway_nacional.cadastral.isbn.util.IsbnValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/isbn")
@Tag(
        name = "ISBN",
        description = "Consulta de dados bibliográficos por ISBN-10 ou ISBN-13, com hedge paralelo entre BrasilAPI, CBL, Google Books, Mercado Editorial e Open Library."
)
public class IsbnController {

    private final IsbnService isbnService;

    public IsbnController(IsbnService isbnService) {
        this.isbnService = isbnService;
    }

    @GetMapping(value = "/{isbn}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar livro por ISBN",
            description = """
                    Aceita ISBN-10 ou ISBN-13 (com ou sem hífens). Valida checksum antes de consultar \
                    upstream. Resultado é cacheado em Redis pelo ISBN normalizado.

                    **Seleção opcional de providers via `?providers=`:** lista separada por vírgula com \
                    aliases lowercase. Suportados: `brasilapi`, `cbl`, `google-books`, `mercado-editorial`, \
                    `open-library`. Se omitido (ou se nenhum alias for válido), o gateway usa todos os 5. \
                    Aliases desconhecidos são silenciosamente ignorados, espelhando o comportamento da BrasilAPI."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Livro encontrado",
                    content = @Content(schema = @Schema(implementation = IsbnResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "ISBN com formato ou checksum inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Nenhum provider encontrou o livro",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os providers estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public IsbnResponse findByIsbn(
            @Parameter(description = "ISBN-10 ou ISBN-13, com ou sem hífens",
                    example = "9788532530803", required = true)
            @PathVariable String isbn,
            @Parameter(description = "Subset de providers para o hedge, separado por vírgula. " +
                    "Aliases: brasilapi, cbl, google-books, mercado-editorial, open-library. " +
                    "Omitido = todos.",
                    example = "cbl,google-books")
            @RequestParam(name = "providers", required = false) String providers
    ) {
        String normalized = IsbnValidator.normalize(isbn);
        if (!IsbnValidator.isValid(normalized)) {
            throw new IsbnInvalidoException(
                    "ISBN inválido: deve ter 10 ou 13 dígitos com checksum válido. Recebido: " + isbn);
        }
        return isbnService.findByIsbn(normalized, IsbnService.parseRequestedProviders(providers));
    }
}
