package br.com.cernebr.gateway_nacional.cadastral.cbo.controller;

import br.com.cernebr.gateway_nacional.cadastral.cbo.dto.CboResponse;
import br.com.cernebr.gateway_nacional.cadastral.cbo.service.CboService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/cadastral/cbo")
@Tag(
        name = "CBO",
        description = "Catálogo Nacional de Ocupações (MTE) com busca em memória de alta performance."
)
public class CboController {

    private final CboService cboService;

    public CboController(CboService cboService) {
        this.cboService = cboService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Busca ocupações por termo (título)",
            description = "Retorna todas as ocupações que contenham a palavra no título. Exemplo: ?busca=operador"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de CBOs encontrados",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = CboResponse.class))))
    })
    public List<CboResponse> searchCbo(
            @Parameter(description = "Termo para busca no título", example = "operador", required = true)
            @RequestParam(value = "busca") String busca
    ) {
        return cboService.searchByTitulo(busca);
    }

    @GetMapping(value = "/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Detalhe de um CBO específico",
            description = "Traz a correspondência exata do CBO pelo código de 6 dígitos."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "CBO encontrado",
                    content = @Content(schema = @Schema(implementation = CboResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Código com formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "CBO não encontrado",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CboResponse getCboByCodigo(
            @Parameter(description = "Código CBO (6 dígitos)", example = "225125", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{6}", message = "O código CBO deve conter exatamente 6 dígitos numéricos.")
            String codigo
    ) {
        return cboService.findByCodigo(codigo);
    }
}
