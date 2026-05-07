package br.com.cernebr.gateway_nacional.saude.controller;

import br.com.cernebr.gateway_nacional.saude.dto.EquipeEGestorResponse;
import br.com.cernebr.gateway_nacional.saude.dto.RepasseFnsResponse;
import br.com.cernebr.gateway_nacional.saude.service.EGestorService;
import br.com.cernebr.gateway_nacional.saude.service.FnsService;
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
@RequestMapping("/api/v1/saude")
@Tag(
        name = "Saúde Pública (APS)",
        description = "Inteligência financeira da Atenção Primária à Saúde — extração automatizada de FNS (repasses federais) e e-Gestor APS (detalhamento por equipe)."
)
public class SaudeFinanceiroController {

    /** SUS-canonical: 6 dígitos. Aceita também 7 dígitos (com verificador) — o service trunca. */
    private static final String IBGE_REGEX = "^[0-9]{6,7}$";
    /** {@code yyyy-MM} — formato canônico ISO de competência mensal. */
    private static final String COMPETENCIA_REGEX = "^[0-9]{4}-(0[1-9]|1[0-2])$";

    private final FnsService fnsService;
    private final EGestorService eGestorService;

    public SaudeFinanceiroController(FnsService fnsService, EGestorService eGestorService) {
        this.fnsService = fnsService;
        this.eGestorService = eGestorService;
    }

    @GetMapping(value = "/fns/{ibge}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Repasses do FNS para um município em uma competência",
            description = """
                    Retorna a lista de repasses financeiros publicados pelo Fundo Nacional \
                    de Saúde para o município (IBGE) na competência informada.

                    O upstream entrega os dados em duas etapas (descoberta de UG/CNPJ → \
                    pagamento detalhado). Em cidades onde os repasses estão sob um Fundo \
                    Municipal de Saúde com CNPJ distinto da prefeitura, o gateway cascateia \
                    automaticamente entre as UGs publicadas para a cidade — mesma estratégia \
                    consagrada do pipeline AutoAPSFinancias.

                    Resultado é cacheado em Redis por **15 dias** (publicação federal é \
                    mensal e raramente revisada na janela)."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de repasses resolvida",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = RepasseFnsResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "IBGE ou competência fora dos padrões esperados",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "FNS bloqueado/indisponível ou Circuit Breaker aberto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<RepasseFnsResponse> findRepassesFns(
            @Parameter(description = "Código IBGE do município (6 dígitos canônicos SUS, ou 7 com dígito verificador)",
                    example = "292270", required = true)
            @PathVariable
            @Pattern(regexp = IBGE_REGEX,
                    message = "O IBGE deve conter exatamente 6 ou 7 dígitos.")
            String ibge,

            @Parameter(description = "Competência no formato yyyy-MM",
                    example = "2024-02", required = true)
            @RequestParam
            @Pattern(regexp = COMPETENCIA_REGEX,
                    message = "A competência deve estar no formato yyyy-MM (ex: 2024-02).")
            String competencia
    ) {
        return fnsService.findRepasses(ibge, competencia);
    }

    @GetMapping(value = "/egestor/{ibge}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Equipes e-Gestor APS detalhadas para um município em uma competência",
            description = """
                    Retorna a lista de equipes da Atenção Primária à Saúde reportadas pelo \
                    portal e-Gestor para o município (IBGE) na competência informada, com \
                    INE, tipo de equipe, valor de custeio agregado e status de suspensão.

                    O fluxo upstream tem três passos JSON (menu de parcelas → componentes \
                    de pagamento → relatório detalhado por componente). O gateway agrega \
                    automaticamente as linhas por `INE + tipoEquipe`, somando o custeio \
                    entre componentes — você recebe **uma linha por equipe**, não por bloco.

                    Resultado é cacheado em Redis por **15 dias**."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de equipes resolvida",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = EquipeEGestorResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "IBGE ou competência fora dos padrões esperados",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "e-Gestor indisponível ou Circuit Breaker aberto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<EquipeEGestorResponse> findEquipesEGestor(
            @Parameter(description = "Código IBGE do município (6 dígitos canônicos SUS, ou 7 com dígito verificador)",
                    example = "292270", required = true)
            @PathVariable
            @Pattern(regexp = IBGE_REGEX,
                    message = "O IBGE deve conter exatamente 6 ou 7 dígitos.")
            String ibge,

            @Parameter(description = "Competência no formato yyyy-MM",
                    example = "2024-02", required = true)
            @RequestParam
            @Pattern(regexp = COMPETENCIA_REGEX,
                    message = "A competência deve estar no formato yyyy-MM (ex: 2024-02).")
            String competencia
    ) {
        return eGestorService.findEquipes(ibge, competencia);
    }
}
