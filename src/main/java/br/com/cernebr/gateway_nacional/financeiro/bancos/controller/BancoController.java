package br.com.cernebr.gateway_nacional.financeiro.bancos.controller;

import br.com.cernebr.gateway_nacional.financeiro.bancos.dto.BancoResponse;
import br.com.cernebr.gateway_nacional.financeiro.bancos.service.BancoService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/bancos")
@Tag(
        name = "Bancos",
        description = "Catálogo de instituições financeiras brasileiras (ISPB + COMPE), com fallback em cascata BrasilAPI → registro local in-memory."
)
public class BancoController {

    private final BancoService bancoService;

    public BancoController(BancoService bancoService) {
        this.bancoService = bancoService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar todas as instituições registradas",
            description = "Retorna a lista completa de bancos com ISPB e código de compensação. Resultado cacheado em Redis por 30 dias (registros bancários mudam raramente)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Catálogo de bancos",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = BancoResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<BancoResponse> findAll() {
        return bancoService.findAll();
    }

    @GetMapping(value = "/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar uma instituição por código de compensação",
            description = """
                    Aceita o código COMPE com ou sem zero à esquerda — `1`, `01` e `001` \
                    são equivalentes e o gateway normaliza para `001` antes de bater no \
                    cache, garantindo uma única entrada Redis por instituição."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Instituição encontrada",
                    content = @Content(schema = @Schema(implementation = BancoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código inválido (esperado 1 a 3 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores estão indisponíveis e o código não está no registro local",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public BancoResponse findByCodigo(
            @Parameter(description = "Código COMPE de 1 a 3 dígitos (zero à esquerda opcional)", example = "001", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{1,3}",
                    message = "O código deve conter de 1 a 3 dígitos numéricos.")
            String codigo
    ) {
        // Normaliza para 3 dígitos antes do service para garantir uma única entrada de cache.
        String normalized = String.format("%03d", Integer.parseInt(codigo));
        return bancoService.findByCodigo(normalized);
    }
}
