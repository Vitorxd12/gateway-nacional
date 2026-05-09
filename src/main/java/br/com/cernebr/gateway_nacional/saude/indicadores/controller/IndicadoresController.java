package br.com.cernebr.gateway_nacional.saude.indicadores.controller;

import br.com.cernebr.gateway_nacional.saude.indicadores.dto.IndicadorSinteticoResponse;
import br.com.cernebr.gateway_nacional.saude.indicadores.service.SisabIndicadoresService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/saude/indicadores")
@Tag(
        name = "Saúde — Indicadores APS",
        description = "Termômetro de Desempenho da APS (Atenção Primária à Saúde) — indicadores Previne Brasil/PMA do Ministério da Saúde, consolidados por quadrimestre e município."
)
public class IndicadoresController {

    /** IBGE municipal — 6 dígitos (formato 5571) ou 7 dígitos (formato 95-up). */
    private static final String IBGE_REGEX = "^[0-9]{6,7}$";
    /** Quadrimestre Previne Brasil — três por ano, formato AAAAQq com q ∈ {1,2,3}. */
    private static final String QUADRIMESTRE_REGEX = "^[0-9]{4}Q[1-3]$";

    private final SisabIndicadoresService service;

    public IndicadoresController(SisabIndicadoresService service) {
        this.service = service;
    }

    @GetMapping(value = "/municipio/{ibge}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar o termômetro APS de um município no quadrimestre",
            description = """
                    Retorna a nota sintética (0-10) do **Previne Brasil/PMA** \
                    para o município no quadrimestre solicitado, mais o \
                    detalhamento dos 7 indicadores oficiais com percentual \
                    atual e meta pactuada.

                    **Cobertura temporal**: o Ministério da Saúde divulga três \
                    quadrimestres por ano:
                    - `Q1` — janeiro a abril
                    - `Q2` — maio a agosto
                    - `Q3` — setembro a dezembro

                    **Cache**: 30 dias por par `(ibge, quadrimestre)`. Uma vez \
                    consolidada a nota, ela é frozen pelo MS até eventual \
                    portaria de reabertura — invalidação manual via Redis caso \
                    necessário.

                    **Pré-requisito operacional**: env \
                    `GATEWAY_SISAB_SIDECAR_URL` apontada para o container do \
                    sidecar (mesmo worker usado pelo módulo SISAB Validação). \
                    Sem ela, a rota responde **503** com orientação."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Termômetro APS resolvido para o município/quadrimestre",
                    content = @Content(schema = @Schema(implementation = IndicadorSinteticoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "IBGE em formato inválido ou quadrimestre fora do padrão AAAAQq",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Sidecar de extração indisponível ou portal DataSUS recusou conexão",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public IndicadorSinteticoResponse consultar(
            @Parameter(description = "Código IBGE do município (6 ou 7 dígitos numéricos).",
                    example = "3550308", required = true)
            @PathVariable
            @Pattern(regexp = IBGE_REGEX,
                    message = "IBGE deve ter 6 ou 7 dígitos numéricos (sem máscara).")
            String ibge,

            @Parameter(description = "Quadrimestre alvo no formato AAAAQq (q ∈ {1,2,3}).",
                    example = "2025Q3", required = true)
            @RequestParam("quadrimestre")
            @Pattern(regexp = QUADRIMESTRE_REGEX,
                    message = "Quadrimestre deve seguir o formato AAAAQq, com q ∈ {1,2,3} (ex: 2025Q3).")
            String quadrimestre
    ) {
        return service.consultar(ibge, quadrimestre);
    }
}
