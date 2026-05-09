package br.com.cernebr.gateway_nacional.saude.ans.controller;

import br.com.cernebr.gateway_nacional.saude.ans.dto.OperadoraAnsResponse;
import br.com.cernebr.gateway_nacional.saude.ans.service.AnsService;
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
@RequestMapping("/api/v1/saude/ans")
@Tag(
        name = "Saúde — ANS",
        description = "Consulta operadoras de planos de saúde registradas na ANS (Agência Nacional de Saúde Suplementar). Útil para validar convênios de pacientes em ERPs."
)
public class AnsController {

    /**
     * Aceita CNPJ (14 dígitos puros) ou Registro ANS (6 dígitos puros).
     * Sem máscara — o ERP é responsável por sanitizar antes da chamada.
     */
    private static final String CNPJ_OU_REGISTRO_REGEX = "^([0-9]{14}|[0-9]{6})$";

    private final AnsService ansService;

    public AnsController(AnsService ansService) {
        this.ansService = ansService;
    }

    @GetMapping(value = "/operadora/{cnpjOuRegistro}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar operadora ANS por CNPJ ou Registro ANS",
            description = """
                    Retorna os dados cadastrais da operadora de plano de saúde \
                    registrada na ANS. Aceita dois formatos no mesmo path \
                    parameter, distinguindo pelo tamanho:

                    - **CNPJ** — exatamente 14 dígitos sem máscara (ex: `29309127000179`)
                    - **Registro ANS** — exatamente 6 dígitos (ex: `326305`)

                    Os dados vêm do snapshot consolidado dos relatórios PDA da \
                    ANS (operadoras ativas + canceladas) servido in-memory; \
                    latência sub-milissegundo, sem dependência externa, sem \
                    Circuit Breaker. O campo `situacao` distingue **ATIVA** de \
                    **CANCELADA** — o ERP recebe o veredito da operadora em \
                    uma única chamada.

                    **Atualização do snapshot**: re-executar \
                    `tmp/build_ans_json.py` (baixa os CSVs PDA mais recentes da \
                    ANS) e fazer um novo build/deploy. A versão dos dados fica \
                    auditável no git history."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Operadora encontrada",
                    content = @Content(schema = @Schema(implementation = OperadoraAnsResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Identificador em formato inválido (esperado: 14 dígitos para CNPJ ou 6 dígitos para Registro ANS)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Operadora não consta no snapshot PDA da ANS",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public OperadoraAnsResponse findByCnpjOuRegistro(
            @Parameter(
                    description = "CNPJ (14 dígitos) ou Registro ANS (6 dígitos), sem máscara",
                    example = "29309127000179",
                    required = true
            )
            @PathVariable
            @Pattern(regexp = CNPJ_OU_REGISTRO_REGEX,
                    message = "Informe um CNPJ com 14 dígitos numéricos ou um Registro ANS com 6 dígitos numéricos (sem máscara).")
            String cnpjOuRegistro
    ) {
        // Discriminação determinística pelo tamanho — o regex já garantiu que
        // são só dígitos, então 14 chars = CNPJ e 6 chars = Registro ANS.
        return cnpjOuRegistro.length() == 14
                ? ansService.findByCnpj(cnpjOuRegistro)
                : ansService.findByRegistroAns(cnpjOuRegistro);
    }
}
