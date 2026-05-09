package br.com.cernebr.gateway_nacional.insights.b2b.controller;

import br.com.cernebr.gateway_nacional.insights.b2b.dto.HealthScoreResponse;
import br.com.cernebr.gateway_nacional.insights.b2b.service.HealthScoreService;
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
@RequestMapping("/api/v1/insights")
@Tag(
        name = "Insights B2B",
        description = "Rotas agregadoras que orquestram múltiplos domínios internos do Gateway num único dossiê — pensadas para onboarding de clínicas no ERP CerneBR."
)
public class InsightsB2BController {

    private static final String CNPJ_REGEX = "\\d{14}";

    private final HealthScoreService healthScoreService;

    public InsightsB2BController(HealthScoreService healthScoreService) {
        this.healthScoreService = healthScoreService;
    }

    @GetMapping(value = "/health-score/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Dossiê B2B agregado a partir de um CNPJ",
            description = """
                    Orquestra três domínios internos para entregar um dossiê de \
                    onboarding completo:

                    **Pipeline:**
                    1. **CNPJ** (sequencial) — `/api/v1/cnpj/{cnpj}` em cascata \
                       BrasilAPI → ReceitaWS → MinhaReceita. O `cnaePrincipal` \
                       devolvido alimenta as etapas seguintes.
                    2. **CNAE** (paralelo) — `/api/v1/cadastral/cnae/{codigo}` \
                       em cascata IBGE → snapshot local. Resolve a descrição \
                       oficial CONCLA para o ramo de atividade.
                    3. **Sinal de saúde** (paralelo) — classifica setor pela \
                       divisão CNAE (Seção Q da CONCLA = 86/87/88) e devolve \
                       orientação consumível pelo ERP. Não é uma chamada CNES \
                       por CNPJ (o CNES é indexado por estabelecimento + IBGE, \
                       não por CNPJ): é um sinal heurístico que dispara o \
                       próximo passo de compliance no ERP quando aplicável.

                    Etapas 2 e 3 rodam em paralelo via **Virtual Threads** \
                    (Java 21, executor com try-with-resources). Cada chamada \
                    interna já tem seu próprio cache Redis e cascata de \
                    fallback — a paralelização aqui economiza puramente \
                    latência agregada da composição."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Dossiê agregado computado",
                    content = @Content(schema = @Schema(implementation = HealthScoreResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CNPJ em formato inválido (esperado 14 dígitos numéricos sem pontuação)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Etapa A (CNPJ) falhou em todos os provedores — sem âncora para o restante da composição",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public HealthScoreResponse healthScore(
            @Parameter(description = "CNPJ com 14 dígitos numéricos, sem pontuação", example = "00000000000191", required = true)
            @PathVariable
            @Pattern(regexp = CNPJ_REGEX, message = "O CNPJ deve conter exatamente 14 dígitos numéricos, sem pontuação.")
            String cnpj
    ) {
        return healthScoreService.buildScore(cnpj);
    }
}
