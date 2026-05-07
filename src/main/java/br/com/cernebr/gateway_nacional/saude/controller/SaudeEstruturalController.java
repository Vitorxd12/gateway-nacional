package br.com.cernebr.gateway_nacional.saude.controller;

import br.com.cernebr.gateway_nacional.saude.dto.ProducaoSisabResponse;
import br.com.cernebr.gateway_nacional.saude.dto.ProfissionalCnesResponse;
import br.com.cernebr.gateway_nacional.saude.service.CnesService;
import br.com.cernebr.gateway_nacional.saude.service.SisabService;
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
        name = "Saúde Pública (APS) — Estrutural",
        description = "Quem trabalha onde e quem enviou produção: extração automatizada de CNES (profissionais por estabelecimento/equipe) e SISAB (validação da produção informada)."
)
public class SaudeEstruturalController {

    private static final String CNES_REGEX = "^[0-9]{7}$";
    private static final String IBGE_REGEX = "^[0-9]{6,7}$";
    private static final String COMPETENCIA_REGEX = "^[0-9]{4}-(0[1-9]|1[0-2])$";

    private final CnesService cnesService;
    private final SisabService sisabService;

    public SaudeEstruturalController(CnesService cnesService, SisabService sisabService) {
        this.cnesService = cnesService;
        this.sisabService = sisabService;
    }

    @GetMapping(value = "/cnes/{cnesBase}/profissionais", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Profissionais cadastrados num estabelecimento CNES",
            description = """
                    Retorna a lista plana de profissionais vinculados ao estabelecimento \
                    (CNES) — agregando todas as equipes registradas via dois endpoints \
                    JSON internos do DATASUS:

                    1. `services/estabelecimentos-equipes/{ibge}{cnes}` — lista equipes (INE, área);
                    2. `services/estabelecimentos-equipes/profissionais/{ibge}{cnes}` — lista \
                       profissionais por equipe.

                    O upstream do CNES é indexado pela chave composta `{ibge}{cnes}` — \
                    o código CNES de 7 dígitos não é único entre municípios, então o \
                    `ibge` é obrigatório.

                    Resultado é cacheado em Redis por **15 dias** (cadastros mudam no \
                    fechamento da competência mensal)."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de profissionais resolvida",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProfissionalCnesResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CNES ou IBGE em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "CNES indisponível ou Circuit Breaker aberto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<ProfissionalCnesResponse> findProfissionaisCnes(
            @Parameter(description = "Código CNES do estabelecimento (7 dígitos)", example = "2469776", required = true)
            @PathVariable
            @Pattern(regexp = CNES_REGEX, message = "O CNES deve conter exatamente 7 dígitos.")
            String cnesBase,

            @Parameter(description = "Código IBGE do município (6 dígitos canônicos SUS, ou 7 com dígito verificador). Obrigatório porque o upstream do CNES indexa por {ibge}{cnes}.",
                    example = "292870", required = true)
            @RequestParam
            @Pattern(regexp = IBGE_REGEX, message = "O IBGE deve conter 6 ou 7 dígitos.")
            String ibge
    ) {
        return cnesService.findProfissionais(cnesBase, ibge);
    }

    @GetMapping(value = "/sisab/{ibge}/producao", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Produção SISAB validada (Aprovado/Reprovado) de um município numa competência",
            description = """
                    Retorna a lista de equipes (CNES + INE) que enviaram produção ao SISAB \
                    na competência informada, com o respectivo status de validação \
                    (`Aprovado` / `Reprovado`).

                    O SISAB é uma aplicação JSF/PrimeFaces — o gateway faz uma submissão \
                    de formulário "best-effort" com captura de `ViewState`. Quando o \
                    upstream exige interação JSF AJAX completa (cascata de selects), a \
                    chamada falha de forma controlada e o gateway devolve **503** com \
                    mensagem clara em vez de pretender sucesso. Embarcar Selenium no \
                    gateway seria 200MB+ de dependência — para deploys que precisam \
                    da extração completa, o caminho é um sidecar Selenium em frente \
                    deste endpoint.

                    Resultado é cacheado em Redis por **15 dias**."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de validações resolvida",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProducaoSisabResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "IBGE ou competência fora dos padrões esperados",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "SISAB indisponível, layout alterado ou exige extração via Selenium",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<ProducaoSisabResponse> findProducaoSisab(
            @Parameter(description = "Código IBGE do município (6 dígitos canônicos SUS, ou 7 com dígito verificador)",
                    example = "292870", required = true)
            @PathVariable
            @Pattern(regexp = IBGE_REGEX, message = "O IBGE deve conter 6 ou 7 dígitos.")
            String ibge,

            @Parameter(description = "Competência no formato yyyy-MM",
                    example = "2024-02", required = true)
            @RequestParam
            @Pattern(regexp = COMPETENCIA_REGEX,
                    message = "A competência deve estar no formato yyyy-MM (ex: 2024-02).")
            String competencia
    ) {
        return sisabService.findProducao(ibge, competencia);
    }
}
