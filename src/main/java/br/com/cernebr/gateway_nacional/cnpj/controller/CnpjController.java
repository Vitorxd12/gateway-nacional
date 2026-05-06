package br.com.cernebr.gateway_nacional.cnpj.controller;

import br.com.cernebr.gateway_nacional.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.cnpj.service.CnpjService;
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
@RequestMapping("/api/v1/cnpj")
@Tag(
        name = "CNPJ",
        description = "Consulta de dados cadastrais de pessoas jurídicas brasileiras, com fallback em cascata entre múltiplos provedores."
)
public class CnpjController {

    private final CnpjService cnpjService;

    public CnpjController(CnpjService cnpjService) {
        this.cnpjService = cnpjService;
    }

    @GetMapping(value = "/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar empresa por CNPJ",
            description = "Resolve o CNPJ consultando BrasilAPI, ReceitaWS e MinhaReceita em cascata. O resultado é cacheado em Redis pelo CNPJ."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Empresa encontrada",
                    content = @Content(schema = @Schema(implementation = CnpjResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CNPJ em formato inválido (esperado 14 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CnpjResponse findByCnpj(
            @Parameter(description = "CNPJ com 14 dígitos numéricos, sem pontuação", example = "00000000000191", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{14}", message = "O CNPJ deve conter exatamente 14 dígitos numéricos, sem pontuação.")
            String cnpj
    ) {
        return cnpjService.findByCnpj(cnpj);
    }
}
