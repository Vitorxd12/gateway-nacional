package br.com.cernebr.gateway_nacional.saude.controller;

import br.com.cernebr.gateway_nacional.saude.dto.AuditoriaInadimplenciaResponse;
import br.com.cernebr.gateway_nacional.saude.service.AuditoriaSaudeService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/saude/auditoria")
@Tag(
        name = "Saúde Pública (APS) — Auditoria Automática",
        description = "Cruza dados de e-Gestor, CNES e SISAB para descobrir qual profissional causou o corte de verba de uma equipe."
)
public class SaudeAuditoriaController {

    private static final String IBGE_REGEX = "^[0-9]{6,7}$";
    private static final String CNES_REGEX = "^[0-9]{7}$";
    private static final String COMPETENCIA_REGEX = "^[0-9]{4}-(0[1-9]|1[0-2])$";

    private final AuditoriaSaudeService auditoriaService;

    public SaudeAuditoriaController(AuditoriaSaudeService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    @GetMapping(value = "/inadimplencia", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Identificar profissionais responsáveis pela suspensão de repasse de uma equipe",
            description = """
                    Cruza dados de **e-Gestor, CNES e SISAB** para descobrir qual \
                    profissional causou o corte de verba de uma equipe APS na competência \
                    informada.

                    Pipeline de cruzamento (executado em paralelo via Virtual Threads, \
                    com cada downstream cacheado em Redis por 15 dias):

                    1. **e-Gestor** entrega as equipes do município com `statusSuspensao` \
                       e motivo. O auditor filtra apenas as suspensas cujo motivo contém \
                       *"produção"* ou *"envio"* (case e acento-insensitive) — a assinatura \
                       financeira de gap de validação no SISAB.
                    2. **CNES** entrega os profissionais cadastrados naquele \
                       estabelecimento, indexados por INE canônico (numérico, sem zeros \
                       à esquerda) para sobreviver às variações de formato de cada portal.
                    3. **SISAB** entrega as validações de produção da competência. O \
                       auditor reduz para o conjunto de INEs *Aprovados* no CNES \
                       requisitado — ausência neste conjunto é o sinal inequívoco de \
                       inadimplência.

                    Para cada equipe que satisfaz os três critérios (suspensa por \
                    produção + presente no CNES + INE não-Aprovado no SISAB), o auditor \
                    devolve um veredito com a lista completa de profissionais — a \
                    actionable list que vai direto pro gestor."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Veredito computado (lista vazia quando nenhuma equipe do CNES está suspensa por produção)",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = AuditoriaInadimplenciaResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "IBGE, CNES ou competência fora dos padrões esperados",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Algum dos upstreams (e-Gestor, CNES ou SISAB) está indisponível ou Circuit Breaker aberto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<AuditoriaInadimplenciaResponse> auditarInadimplencia(
            @Parameter(description = "Código IBGE do município (6 dígitos canônicos SUS, ou 7 com dígito verificador)",
                    example = "292870", required = true)
            @RequestParam
            @Pattern(regexp = IBGE_REGEX, message = "O IBGE deve conter 6 ou 7 dígitos.")
            String ibge,

            @Parameter(description = "Código CNES (7 dígitos) do estabelecimento auditado",
                    example = "2469776", required = true)
            @RequestParam
            @Pattern(regexp = CNES_REGEX, message = "O CNES deve conter exatamente 7 dígitos.")
            String cnes,

            @Parameter(description = "Competência no formato yyyy-MM",
                    example = "2024-02", required = true)
            @RequestParam
            @Pattern(regexp = COMPETENCIA_REGEX,
                    message = "A competência deve estar no formato yyyy-MM (ex: 2024-02).")
            String competencia
    ) {
        return auditoriaService.auditarEquipes(ibge, cnes, competencia);
    }
}
