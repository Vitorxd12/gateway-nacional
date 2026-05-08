package br.com.cernebr.gateway_nacional.fiscal.cfop.controller;

import br.com.cernebr.gateway_nacional.fiscal.cfop.dto.CfopResponse;
import br.com.cernebr.gateway_nacional.fiscal.cfop.service.CfopService;
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
@RequestMapping("/api/v1/fiscal/cfop")
@Tag(
        name = "Fiscal — CFOP",
        description = "Código Fiscal de Operações e Prestações (Convênio SINIEF). Servido in-memory, latência sub-milissegundo."
)
public class CfopController {

    /** CFOP é canonicamente um código de 4 dígitos puros (sem ponto, sem hífen). */
    private static final String CFOP_REGEX = "^[1-7][0-9]{3}$";

    private final CfopService cfopService;

    public CfopController(CfopService cfopService) {
        this.cfopService = cfopService;
    }

    @GetMapping(value = "/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar CFOP por código",
            description = """
                    Retorna a descrição oficial e a aplicação (notas explicativas \
                    do Convênio SINIEF) para o código CFOP informado.

                    O **primeiro dígito** do CFOP indica direção e escopo da operação:
                    - `1xxx` — entrada intra-estadual
                    - `2xxx` — entrada interestadual
                    - `3xxx` — entrada do exterior
                    - `5xxx` — saída intra-estadual
                    - `6xxx` — saída interestadual
                    - `7xxx` — saída para o exterior

                    A tabela CFOP é virtualmente estática (Convênio SINIEF está \
                    estabilizado há duas décadas), por isso é servida diretamente \
                    da memória da aplicação — **sem chamadas externas, sem cache \
                    Redis, sem Circuit Breaker**. Latência típica é \
                    sub-milissegundo (lookup em HashMap O(1))."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "CFOP encontrado",
                    content = @Content(schema = @Schema(implementation = CfopResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código CFOP em formato inválido (esperado: 4 dígitos iniciando com 1-7)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Código CFOP não consta na tabela do Convênio SINIEF",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CfopResponse findByCodigo(
            @Parameter(description = "Código CFOP de 4 dígitos (primeiro entre 1-7)",
                    example = "5102", required = true)
            @PathVariable
            @Pattern(regexp = CFOP_REGEX,
                    message = "O código CFOP deve conter exatamente 4 dígitos numéricos iniciando entre 1-7 (ex: 5102).")
            String codigo
    ) {
        return cfopService.findByCodigo(codigo);
    }
}
