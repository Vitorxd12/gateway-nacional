package br.com.cernebr.gateway_nacional.cadastral.ddd.controller;

import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddResponse;
import br.com.cernebr.gateway_nacional.cadastral.ddd.service.DddService;
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
@RequestMapping("/api/v1/cadastral/ddd")
@Tag(
        name = "DDD",
        description = "Consulta do quadro nacional de DDDs da ANATEL — mapeia código nacional (2 dígitos) para UF e cidades atendidas. Single provider (ANATEL é fonte canônica), snapshot cacheado com RAC (soft 90d / hard 365d)."
)
public class DddController {

    private final DddService dddService;

    public DddController(DddService dddService) {
        this.dddService = dddService;
    }

    @GetMapping(value = "/{ddd}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar UF e cidades atendidas por um DDD",
            description = "Retorna o estado e a lista de cidades atendidas pelo código nacional informado. Lookup feito em memória sobre o snapshot ANATEL cacheado."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "DDD localizado",
                    content = @Content(schema = @Schema(implementation = DddResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "DDD com formato inválido (esperado 2 dígitos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "DDD não consta no quadro nacional",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "ANATEL indisponível (sem snapshot cacheado)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DddResponse findByDdd(
            @Parameter(description = "Código DDD com 2 dígitos numéricos", example = "11", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{2}",
                    message = "DDD deve conter exatamente 2 dígitos numéricos.")
            String ddd
    ) {
        return dddService.findByDdd(ddd);
    }
}
