package br.com.cernebr.gateway_nacional.juridico.controller;

import br.com.cernebr.gateway_nacional.juridico.cnd.dto.CndResponse;
import br.com.cernebr.gateway_nacional.juridico.cnd.service.CndService;
import br.com.cernebr.gateway_nacional.juridico.processos.dto.ProcessoResponse;
import br.com.cernebr.gateway_nacional.juridico.processos.service.ProcessosService;
import br.com.cernebr.gateway_nacional.juridico.sancoes.dto.SancaoResponse;
import br.com.cernebr.gateway_nacional.juridico.sancoes.service.SancoesService;
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
@RequestMapping("/api/v1/juridico")
@Tag(
        name = "Jurídico — Compliance",
        description = "Sanções administrativas (CGU/CEIS), Certidões Negativas (CND via sidecar) e processos judiciais (DataJud/CNJ). Insumo para due diligence em RH, Compras e contratos B2B."
)
public class ComplianceController {

    private static final String CNPJ_REGEX = "^[0-9]{14}$";
    private static final String NUMERO_PROCESSO_REGEX = "^[0-9]{20}$";
    private static final String TIPO_CND_REGEX = "RFB|FGTS|TST";

    private final SancoesService sancoesService;
    private final CndService cndService;
    private final ProcessosService processosService;

    public ComplianceController(SancoesService sancoesService,
                                CndService cndService,
                                ProcessosService processosService) {
        this.sancoesService = sancoesService;
        this.cndService = cndService;
        this.processosService = processosService;
    }

    /* ----------------------- Sanções (CGU / CEIS) ----------------------- */

    @GetMapping(value = "/sancoes/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar sanções administrativas publicadas contra um CNPJ",
            description = """
                    Consulta o **CEIS** (Cadastro Nacional de Empresas Inidôneas e \
                    Suspensas) publicado pela CGU no Portal da Transparência. \
                    Retorna **todas** as sanções vigentes ou históricas associadas \
                    ao CNPJ — o ERP decide a regra de negócio (impedir contrato, \
                    exigir manifestação, registrar no dossier).

                    **Cache:** 7 dias por CNPJ. Sanções entram no portal com \
                    semanas de delay frente ao DOU; cachear menos do que isso \
                    seria gasto de quota da CGU sem ganho real de freshness.

                    **Pré-requisito operacional:** o cliente exige a env \
                    `GATEWAY_JURIDICO_SANCOES_CGU_API_KEY` configurada após \
                    cadastro em `portaldatransparencia.gov.br/api-de-dados/cadastrar-email`. \
                    Sem a chave, a rota responde **503** com a orientação de \
                    como obter o credencial."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Sanções resolvidas (lista pode ser vazia: CNPJ sem registros = limpo)",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = SancaoResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CNPJ em formato inválido (esperado: 14 dígitos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "API CGU indisponível, chave ausente ou Circuit Breaker aberto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<SancaoResponse> consultarSancoes(
            @Parameter(description = "CNPJ alvo (14 dígitos sem máscara)", example = "00000000000191", required = true)
            @PathVariable
            @Pattern(regexp = CNPJ_REGEX,
                    message = "Informe um CNPJ com exatamente 14 dígitos numéricos (sem máscara).")
            String cnpj
    ) {
        return sancoesService.findByCnpj(cnpj).sancoes();
    }

    /* ----------------------- CND (Certidões Negativas) ----------------------- */

    @GetMapping(value = "/cnd/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Emitir / validar uma Certidão Negativa de Débitos",
            description = """
                    Delega a navegação dos portais oficiais (Receita Federal, \
                    FGTS/Caixa, TST) ao **sidecar Python/Selenium** compartilhado \
                    com o módulo SISAB. O sidecar resolve a cascata de formulários \
                    JSF/captcha e devolve o documento estruturado.

                    **Sem cache** — a CND tem código de controle único por \
                    emissão e o ERP precisa do documento corrente para anexar \
                    em contrato. Cachear introduziria risco de apresentar uma \
                    certidão vencida ou códigos de controle conflitantes.

                    **Tipos suportados:**
                    - `RFB` — Receita Federal (Tributos federais + Dívida Ativa da União)
                    - `FGTS` — Caixa Econômica Federal (regularidade do FGTS)
                    - `TST` — Tribunal Superior do Trabalho (CNDT)

                    **Pré-requisito operacional:** env `GATEWAY_SISAB_SIDECAR_URL` \
                    apontada para o container do sidecar (geralmente \
                    `http://sisab-sidecar:8000` no docker-compose). Sem ela, a \
                    rota responde **503** com a orientação."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Certidão emitida com sucesso",
                    content = @Content(schema = @Schema(implementation = CndResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CNPJ inválido ou tipo desconhecido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Sidecar de scraping indisponível ou Circuit Breaker aberto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CndResponse emitirCnd(
            @Parameter(description = "CNPJ titular da certidão (14 dígitos sem máscara)", example = "00000000000191", required = true)
            @PathVariable
            @Pattern(regexp = CNPJ_REGEX,
                    message = "Informe um CNPJ com exatamente 14 dígitos numéricos (sem máscara).")
            String cnpj,

            @Parameter(description = "Tipo da CND a emitir (RFB, FGTS, TST)", example = "RFB", required = true,
                    schema = @Schema(allowableValues = {"RFB", "FGTS", "TST"}))
            @RequestParam("tipo")
            @Pattern(regexp = TIPO_CND_REGEX,
                    flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "tipo deve ser uma de: RFB, FGTS, TST.")
            String tipo
    ) {
        return cndService.emitir(cnpj, tipo);
    }

    /* ----------------------- Processos (DataJud) ----------------------- */

    @GetMapping(value = "/processos/{numeroProcesso}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar metadados de um processo judicial",
            description = """
                    Consulta a **API Pública DataJud** do CNJ por numeração única \
                    CNJ (20 dígitos puros, sem máscara). O tribunal de origem é \
                    derivado das posições 14-15 do número (par J.TR), de modo \
                    que o cliente já dirige a consulta ao alias correto \
                    (`api_publica_tjsp`, `api_publica_trt2`, etc).

                    **Cobertura:**
                    - `J=1` STF, `J=3` STJ
                    - `J=4` Justiça Federal (TRF1..TRF6)
                    - `J=5` Justiça do Trabalho (TRT1..TRT24)
                    - `J=8` Justiça Estadual (TJAC..TJTO, todas as UFs)

                    Justiça Eleitoral (J=6) e Militar (J=7) ainda não estão \
                    cobertas.

                    **Cache:** 24h por número. Tribunais publicam atualizações \
                    no DataJud em batches D+1; janelas mais curtas só consomem \
                    quota sem ganho real."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Processo encontrado",
                    content = @Content(schema = @Schema(implementation = ProcessoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Numeração CNJ inválida (esperado: 20 dígitos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Processo não localizado no DataJud",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "DataJud indisponível ou Circuit Breaker aberto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public ProcessoResponse consultarProcesso(
            @Parameter(description = "Numeração única CNJ (20 dígitos sem máscara)", example = "00008323520184013202", required = true)
            @PathVariable
            @Pattern(regexp = NUMERO_PROCESSO_REGEX,
                    message = "Informe a numeração CNJ com 20 dígitos numéricos (sem máscara).")
            String numeroProcesso
    ) {
        return processosService.findByNumero(numeroProcesso);
    }
}
