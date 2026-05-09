package br.com.cernebr.gateway_nacional.cadastral.cep.controller;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.cadastral.cep.service.CepService;
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
@RequestMapping("/api/v1/cep")
@Tag(
        name = "CEP",
        description = "Consulta de endereços brasileiros a partir do Código de Endereçamento Postal, com fallback em cascata entre múltiplos provedores."
)
public class CepController {

    private final CepService cepService;

    public CepController(CepService cepService) {
        this.cepService = cepService;
    }

    @GetMapping(value = "/{cep}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar endereço por CEP",
            description = "Resolve o CEP consultando ViaCEP, BrasilAPI e AwesomeAPI em cascata. O resultado é cacheado em Redis pelo CEP."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Endereço encontrado",
                    content = @Content(schema = @Schema(implementation = CepResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CEP em formato inválido (esperado 8 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CepResponse findByCep(
            @Parameter(description = "CEP com 8 dígitos numéricos, sem hífen", example = "01001000", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{8}", message = "O CEP deve conter exatamente 8 dígitos numéricos, sem hífen.")
            String cep
    ) {
        return cepService.findByCep(cep);
    }
}
