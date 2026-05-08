package br.com.cernebr.gateway_nacional.fiscal.cest.controller;

import br.com.cernebr.gateway_nacional.fiscal.cest.dto.CestResponse;
import br.com.cernebr.gateway_nacional.fiscal.cest.service.CestService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fiscal/cest")
@Tag(
        name = "Fiscal — CEST",
        description = "Código Especificador da Substituição Tributária (Convênio ICMS 142/2018). " +
                "Servido in-memory, latência sub-milissegundo."
)
public class CestController {

    /** CEST canônico = 7 dígitos puros (sem pontuação). */
    private static final String CEST_REGEX = "^[0-9]{7}$";

    /** NCM canônico = 2 a 8 dígitos puros (capítulo a posição completa, sem pontuação). */
    private static final String NCM_REGEX = "^[0-9]{2,8}$";

    private final CestService cestService;

    public CestController(CestService cestService) {
        this.cestService = cestService;
    }

    @GetMapping(value = "/{cest}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar CEST por código",
            description = """
                    Retorna a entrada canônica do CEST informado, com NCM associado e \
                    descrição oficial extraídas do Convênio ICMS 142/2018.

                    O CEST é um código de **7 dígitos** estruturado como **SS-III-DD**:
                    - `SS` — segmento (01–28; ex: `01` Autopeças, `17` Alimentos, `28` Vendas a varejo)
                    - `III` — item dentro do segmento
                    - `DD` — especificação do produto

                    A tabela CEST é virtualmente estática (atualizações raras, via novos \
                    convênios CONFAZ), por isso é servida diretamente da memória da \
                    aplicação — **sem chamadas externas, sem cache Redis, sem Circuit \
                    Breaker**. Latência típica é sub-milissegundo (lookup em HashMap O(1))."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "CEST encontrado",
                    content = @Content(schema = @Schema(implementation = CestResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código CEST em formato inválido (esperado: 7 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "CEST não consta na tabela do Convênio ICMS 142/2018",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CestResponse findByCest(
            @Parameter(description = "Código CEST de 7 dígitos (sem pontuação)",
                    example = "0100100", required = true)
            @PathVariable
            @Pattern(regexp = CEST_REGEX,
                    message = "O código CEST deve conter exatamente 7 dígitos numéricos sem pontuação (ex: 0100100).")
            String cest
    ) {
        return cestService.findByCest(cest);
    }

    @GetMapping(value = "/ncm/{ncm}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar CESTs aplicáveis a um NCM",
            description = """
                    Retorna **todos** os CESTs aplicáveis a um determinado NCM. \
                    Esta é a consulta dominante em fluxos de emissão de NF-e: o ERP \
                    já conhece o NCM do produto e precisa descobrir se há \
                    Substituição Tributária aplicável.

                    Comportamento esperado:
                    - **Lista não vazia** — o NCM possui um ou mais CESTs candidatos. \
                      O ERP deve escolher o segmento aplicável ao seu cenário comercial.
                    - **Lista vazia (`[]`, HTTP 200)** — o NCM **não está sujeito a ICMS-ST** \
                      sob o convênio vigente. *Não* é erro: é a resposta fiscal correta.

                    O lookup é direto sobre o NCM informado (sem fallback para \
                    capítulo/posição). Se você passar um NCM parcial, o gateway \
                    retornará apenas os CESTs cujo NCM cadastrado coincide \
                    literalmente."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista (possivelmente vazia) de CESTs associados ao NCM",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = CestResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "NCM em formato inválido (esperado: 2 a 8 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<CestResponse> findByNcm(
            @Parameter(description = "Código NCM de 2 a 8 dígitos (sem pontuação)",
                    example = "38151210", required = true)
            @PathVariable
            @Pattern(regexp = NCM_REGEX,
                    message = "O código NCM deve conter de 2 a 8 dígitos numéricos sem pontuação (ex: 38151210).")
            String ncm
    ) {
        return cestService.findByNcm(ncm);
    }
}
