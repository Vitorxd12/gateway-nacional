package br.com.cernebr.gateway_nacional.cadastral.simples.controller;

import br.com.cernebr.gateway_nacional.cadastral.simples.dto.SimplesNacionalResponse;
import br.com.cernebr.gateway_nacional.cadastral.simples.service.SimplesNacionalService;
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
@RequestMapping("/api/v1/cadastral/simples")
@Tag(
        name = "Simples Nacional",
        description = "Consulta de enquadramento em Simples Nacional e SIMEI com cascata: scraper oficial da Receita Federal → fallback ReceitaWS."
)
public class SimplesNacionalController {

    private final SimplesNacionalService simplesNacionalService;

    public SimplesNacionalController(SimplesNacionalService simplesNacionalService) {
        this.simplesNacionalService = simplesNacionalService;
    }

    @GetMapping(value = "/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar enquadramento no Simples Nacional / SIMEI",
            description = "Resolve o CNPJ contra o portal Consulta Optantes da Receita Federal via web scraping resiliente. Em janelas de CAPTCHA ou indisponibilidade, cai para o fallback ReceitaWS, que já entrega Simples/SIMEI no payload de CNPJ. Resposta cacheada por 12h (soft) / 24h (hard)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Enquadramento resolvido",
                    content = @Content(schema = @Schema(implementation = SimplesNacionalResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CNPJ em formato inválido (esperado 14 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Portal oficial e fallback indisponíveis simultaneamente",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public SimplesNacionalResponse findByCnpj(
            @Parameter(description = "CNPJ com 14 dígitos numéricos, sem pontuação", example = "00000000000191", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{14}", message = "O CNPJ deve conter exatamente 14 dígitos numéricos, sem pontuação.")
            String cnpj
    ) {
        return simplesNacionalService.findByCnpj(cnpj);
    }
}
