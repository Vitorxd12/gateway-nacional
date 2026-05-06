package br.com.cernebr.gateway_nacional.fipe.controller;

import br.com.cernebr.gateway_nacional.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.fipe.service.FipeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/fipe")
@Tag(
        name = "FIPE",
        description = "Cotação de veículos pela Tabela FIPE, com fallback em cascata BrasilAPI → Parallelum."
)
public class FipeController {

    private static final String CODIGO_FIPE_REGEX = "^[0-9]{6}-[0-9]{1}$";
    private static final String ANO_MODELO_REGEX = "\\d{4,5}";

    private final FipeService fipeService;

    public FipeController(FipeService fipeService) {
        this.fipeService = fipeService;
    }

    @GetMapping(value = "/preco/{codigoFipe}/{anoModelo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar preço FIPE de um veículo",
            description = """
                    Retorna a cotação FIPE referente ao código e ano modelo informados. \
                    Aceita o ano calendário com 4 dígitos (ex: `2024`) ou o sentinela \
                    `32000` para indicar Zero KM, conforme convenção FIPE.

                    Ordem da cascata: **BrasilAPI → Parallelum**. O resultado é cacheado \
                    em Redis por 15 dias (FIPE publica atualizações mensalmente; o TTL \
                    garante que o cliente veja o novo mês-de-referência logo após cada \
                    ciclo de publicação)."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Cotação resolvida com sucesso",
                    content = @Content(schema = @Schema(implementation = FipePrecoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código FIPE inválido (esperado padrão 000000-0) ou ano em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public FipePrecoResponse findPreco(
            @Parameter(description = "Código FIPE no padrão 000000-0", example = "005340-0", required = true)
            @PathVariable
            @Pattern(regexp = CODIGO_FIPE_REGEX,
                    message = "O código FIPE deve seguir o padrão 000000-0 (6 dígitos, hífen, 1 dígito).")
            String codigoFipe,

            @Parameter(description = "Ano modelo (4 dígitos) ou 32000 para Zero KM", example = "2018", required = true)
            @PathVariable
            @Pattern(regexp = ANO_MODELO_REGEX,
                    message = "O ano modelo deve conter 4 dígitos (ex: 2024) ou ser 32000 para Zero KM.")
            String anoModelo
    ) {
        return fipeService.findPreco(codigoFipe, anoModelo);
    }
}
