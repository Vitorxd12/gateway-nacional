package br.com.cernebr.gateway_nacional.cadastral.cnd.controller;

import br.com.cernebr.gateway_nacional.cadastral.cnd.dto.CndConsolidadaResponse;
import br.com.cernebr.gateway_nacional.cadastral.cnd.service.CndConsolidadaService;
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
@RequestMapping("/api/v1/cadastral/cnd")
@Tag(
        name = "CNDs Consolidadas",
        description = "Emissão e validação paralela das certidões negativas Trabalhista (TST), FGTS (Caixa) e Federal (PGFN/Receita) com degradação graciosa por nó."
)
public class CndController {

    private final CndConsolidadaService cndService;

    public CndController(CndConsolidadaService cndService) {
        this.cndService = cndService;
    }

    @GetMapping(value = "/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar CNDs consolidadas por CNPJ",
            description = "Dispara emissão/validação das três certidões (TST, FGTS, Federal) em paralelo via virtual threads. p99 ≈ latência do nó mais lento, não a soma. Degradação graciosa: se um nó cair (ex.: Caixa 503), o sub-record correspondente vem com status=INDISPONIVEL ao invés de quebrar a resposta inteira."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Resposta consolidada (pode conter sub-records INDISPONIVEL — verificar degradado/certidoesResolvidas)",
                    content = @Content(schema = @Schema(implementation = CndConsolidadaResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "CNPJ em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "Catástrofe total — os três provedores caíram simultaneamente",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CndConsolidadaResponse findByCnpj(
            @Parameter(description = "CNPJ com 14 dígitos numéricos, sem pontuação", example = "00000000000191", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{14}", message = "O CNPJ deve conter exatamente 14 dígitos numéricos, sem pontuação.")
            String cnpj
    ) {
        return cndService.findByCnpj(cnpj);
    }
}
