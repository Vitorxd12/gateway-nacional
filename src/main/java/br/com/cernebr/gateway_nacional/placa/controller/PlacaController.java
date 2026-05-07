package br.com.cernebr.gateway_nacional.placa.controller;

import br.com.cernebr.gateway_nacional.placa.dto.PlacaResponse;
import br.com.cernebr.gateway_nacional.placa.service.PlacaService;
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

import java.util.Locale;

@Validated
@RestController
@RequestMapping("/api/v1/placa")
@Tag(
        name = "Placa",
        description = "Identificação de veículos a partir da placa (padrão antigo e Mercosul), com fallback em cascata WDApi → Keplaca e mascaramento automático de chassi."
)
public class PlacaController {

    /**
     * Aceita ambos os formatos:
     * <ul>
     *   <li>Antigo: {@code ABC1234} (3 letras + 4 dígitos)</li>
     *   <li>Mercosul: {@code ABC1D23} (3 letras + 1 dígito + 1 letra + 2 dígitos)</li>
     * </ul>
     */
    private static final String PLACA_REGEX = "^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$";

    private final PlacaService placaService;

    public PlacaController(PlacaService placaService) {
        this.placaService = placaService;
    }

    @GetMapping(value = "/{placa}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Identificar veículo por placa",
            description = """
                    Aceita placas no padrão **antigo** (`ABC1234`) e no padrão **Mercosul** \
                    (`ABC1D23`), case-insensitive. A placa é normalizada (uppercase, sem \
                    hífen) antes de bater no cache e nos provedores upstream.

                    Ordem da cascata: **WDApi → Keplaca**. Resultado cacheado em Redis por \
                    365 dias — a vinculação placa-veículo é essencialmente permanente.

                    **Privacidade:** o chassi devolvido é sempre mascarado \
                    (`***` + últimos 4 caracteres) ou `null`. O chassi completo nunca cruza \
                    a fronteira do gateway, mesmo em logs."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Veículo identificado com sucesso",
                    content = @Content(schema = @Schema(implementation = PlacaResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Placa em formato inválido (esperado padrão antigo ou Mercosul)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public PlacaResponse findByPlaca(
            @Parameter(description = "Placa nos padrões antigo ou Mercosul, case-insensitive",
                    example = "ABC1D23", required = true)
            @PathVariable
            @Pattern(regexp = PLACA_REGEX,
                    message = "A placa deve seguir o padrão antigo (ABC1234) ou Mercosul (ABC1D23).")
            String placa
    ) {
        // Normalização defensiva — uppercase e remoção de hífen, mesmo
        // sabendo que a regex já barra o hífen. Se a regex relaxar no
        // futuro, a normalização continua válida.
        String normalized = placa.toUpperCase(Locale.ROOT).replace("-", "");
        return placaService.findByPlaca(normalized);
    }
}
