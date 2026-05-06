package br.com.cernebr.gateway_nacional.rastreio.controller;

import br.com.cernebr.gateway_nacional.rastreio.dto.RastreioResponse;
import br.com.cernebr.gateway_nacional.rastreio.service.RastreioService;
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
@RequestMapping("/api/v1/rastreio")
@Tag(
        name = "Rastreio",
        description = "Rastreamento de encomendas dos Correios, com fallback em cascata entre Link&Track, BrasilAPI e Correios Oficial."
)
public class RastreioController {

    /**
     * Padrão oficial dos Correios: 2 letras + 9 dígitos + 2 letras
     * (ex: {@code LB123456789BR}). Aceitamos ambas as caixas; o service
     * normaliza para uppercase antes de bater no cache.
     */
    private static final String CODIGO_REGEX = "^[A-Za-z]{2}[0-9]{9}[A-Za-z]{2}$";

    private final RastreioService rastreioService;

    public RastreioController(RastreioService rastreioService) {
        this.rastreioService = rastreioService;
    }

    @GetMapping(value = "/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar rastreio de uma encomenda",
            description = """
                    Retorna a linha do tempo de eventos da encomenda — ordenada do mais \
                    recente para o mais antigo. O campo `isEntregue` é derivado do scan \
                    dos eventos por status contendo `ENTREG` (case-insensitive), e é \
                    confiável independentemente de qual provedor respondeu.

                    Ordem da cascata: **Link&Track → BrasilAPI → Correios Oficial**. \
                    O resultado é cacheado em Redis por 1 hora — janela curta porque \
                    eventos de rastreio são sensíveis ao tempo e clientes finais \
                    consultam várias vezes ao dia."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Histórico de rastreio resolvido",
                    content = @Content(schema = @Schema(implementation = RastreioResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código de rastreio inválido (esperado padrão Correios: 2 letras + 9 dígitos + 2 letras)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public RastreioResponse findByCodigo(
            @Parameter(description = "Código de rastreio no padrão oficial dos Correios (case-insensitive)",
                    example = "LB123456789BR", required = true)
            @PathVariable
            @Pattern(regexp = CODIGO_REGEX,
                    message = "O código de rastreio deve seguir o padrão dos Correios: 2 letras + 9 dígitos + 2 letras (ex: LB123456789BR).")
            String codigo
    ) {
        return rastreioService.findByCodigo(codigo);
    }
}
