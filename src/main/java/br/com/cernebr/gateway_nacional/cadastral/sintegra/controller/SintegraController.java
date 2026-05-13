package br.com.cernebr.gateway_nacional.cadastral.sintegra.controller;

import br.com.cernebr.gateway_nacional.cadastral.sintegra.dto.SintegraResponse;
import br.com.cernebr.gateway_nacional.cadastral.sintegra.service.SintegraService;
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
@RequestMapping("/api/v1/cadastral/sintegra")
@Tag(
        name = "Sintegra / IE",
        description = "Inscrição estadual unificada via hedge entre Cadastro Centralizado de Contribuintes (CCC/SVRS) e agregador aberto."
)
public class SintegraController {

    private final SintegraService sintegraService;

    public SintegraController(SintegraService sintegraService) {
        this.sintegraService = sintegraService;
    }

    @GetMapping(value = "/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar IE por CNPJ (varredura nacional)",
            description = "Sem UF informada, varre o CCC/SVRS por todas as UFs cobertas e usa agregador como fallback paralelo. Útil para descobrir em qual estado a empresa mantém inscrição ativa."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Inscrição estadual localizada",
                    content = @Content(schema = @Schema(implementation = SintegraResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "Nenhuma inscrição estadual encontrada para o CNPJ",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "400",
                    description = "CNPJ em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "CCC/SVRS e agregador indisponíveis simultaneamente",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public SintegraResponse findByCnpj(
            @Parameter(description = "CNPJ com 14 dígitos numéricos, sem pontuação", example = "07526557000100", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{14}", message = "O CNPJ deve conter exatamente 14 dígitos numéricos, sem pontuação.")
            String cnpj
    ) {
        return sintegraService.findByCnpj(cnpj, null);
    }

    @GetMapping(value = "/{cnpj}/{uf}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar IE por CNPJ e UF",
            description = "Recorte direto à UF informada — caminho preferencial para integradores que já conhecem o estado de origem da empresa. Mais barato em quota dos provedores: o hedge resolve em ~600ms quando CCC cobre a UF."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Inscrição estadual localizada na UF",
                    content = @Content(schema = @Schema(implementation = SintegraResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "Nenhuma IE para o par CNPJ/UF",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "400",
                    description = "CNPJ ou UF em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "Provedores indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public SintegraResponse findByCnpjAndUf(
            @Parameter(description = "CNPJ com 14 dígitos numéricos, sem pontuação", example = "07526557000100", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{14}", message = "O CNPJ deve conter exatamente 14 dígitos numéricos, sem pontuação.")
            String cnpj,
            @Parameter(description = "Sigla da Unidade Federativa (2 letras)", example = "RS", required = true)
            @PathVariable
            @Pattern(regexp = "[A-Za-z]{2}", message = "A UF deve conter exatamente 2 letras.")
            String uf
    ) {
        return sintegraService.findByCnpj(cnpj, uf);
    }
}
