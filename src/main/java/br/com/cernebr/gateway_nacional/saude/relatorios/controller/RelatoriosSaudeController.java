package br.com.cernebr.gateway_nacional.saude.relatorios.controller;

import br.com.cernebr.gateway_nacional.saude.relatorios.dto.RelatorioDesempenhoApsResponse;
import br.com.cernebr.gateway_nacional.saude.relatorios.service.TermometroApsService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/saude/relatorios")
@Tag(
        name = "Saúde Pública (APS) — Relatórios",
        description = "Relatórios cruzados entre o Termômetro Previne Brasil/PMA e o cadastro CNES — entregam ao gestor municipal o mapa pronto de unidades para busca ativa."
)
public class RelatoriosSaudeController {

    private static final String IBGE_REGEX = "^[0-9]{6,7}$";
    /** Mesmo formato adotado pelo IndicadoresController — AAAAQq, q ∈ {1,2,3}. */
    private static final String QUADRIMESTRE_REGEX = "^[0-9]{4}Q[1-3]$";

    private final TermometroApsService termometroApsService;

    public RelatoriosSaudeController(TermometroApsService termometroApsService) {
        this.termometroApsService = termometroApsService;
    }

    @GetMapping(value = "/termometro-aps", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Termômetro APS do município com mapa de unidades para busca ativa",
            description = """
                    Cruza, **em paralelo**, o Termômetro Previne Brasil/PMA \
                    (`/api/v1/saude/indicadores/municipio/{ibge}?quadrimestre={Q}`) \
                    com o cadastro CNES de estabelecimentos do município. \
                    Devolve a nota financeira do quadrimestre + a lista \
                    priorizada de UBS/USF/UAPS com `statusRisco` derivado.

                    ## Valor para a gestão municipal

                    O gestor que hoje precisa abrir dois portais (SISAB para a \
                    nota; CNES para o mapa) e cruzar manualmente em planilha \
                    qual unidade é responsável pela queda do indicador agora \
                    recebe a resposta pronta:

                    - `notaFinal < 6.0` → todas as UBS marcadas **URGENTE** com a \
                      lista de indicadores defasados como observação. Disparar \
                      busca ativa imediata.
                    - `notaFinal ≥ 6.0` mas meta não alcançada → **ATENCAO** com \
                      a lista de indicadores defasados.
                    - Meta alcançada → **OK**.

                    O endpoint **não tem cache próprio** — ambos os colaboradores \
                    (SISAB e CNES) já cacheiam em Redis; cachear aqui também \
                    duplicaria armazenamento e dificultaria a invalidação \
                    cirúrgica por município.

                    **Pré-requisito**: env `GATEWAY_SISAB_SIDECAR_URL` apontada para \
                    o sidecar Python (mesmo do módulo SISAB Validação), senão \
                    a etapa SISAB cai para **503**."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Relatório consolidado",
                    content = @Content(schema = @Schema(implementation = RelatorioDesempenhoApsResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "IBGE em formato inválido ou quadrimestre fora do padrão AAAAQq",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Sidecar SISAB indisponível, DataSUS recusou conexão, ou CNES sem dados",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public RelatorioDesempenhoApsResponse termometro(
            @Parameter(description = "Código IBGE do município (6 ou 7 dígitos numéricos).",
                    example = "355030", required = true)
            @RequestParam("ibge")
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
        return termometroApsService.build(ibge, quadrimestre);
    }
}
