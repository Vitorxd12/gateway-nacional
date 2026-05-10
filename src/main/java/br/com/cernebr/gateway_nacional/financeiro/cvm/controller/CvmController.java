package br.com.cernebr.gateway_nacional.financeiro.cvm.controller;

import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CorretoraResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundoDetailResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundosPageResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.service.CvmCorretorasService;
import br.com.cernebr.gateway_nacional.financeiro.cvm.service.CvmFundosService;
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
@RequestMapping("/api/v1/financeiro/cvm")
@Tag(
        name = "CVM",
        description = "Cadastro CVM — corretoras (cad_intermed.zip) e fundos de investimento (cad_fi.csv). Snapshot baixado uma vez por ciclo de cache (RAC: hard 30d / soft 7d) e mantido em memória para lookups instantâneos por CNPJ."
)
public class CvmController {

    private final CvmCorretorasService corretorasService;
    private final CvmFundosService fundosService;

    public CvmController(CvmCorretorasService corretorasService,
                         CvmFundosService fundosService) {
        this.corretorasService = corretorasService;
        this.fundosService = fundosService;
    }

    @GetMapping(value = "/corretoras", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar todas as corretoras autorizadas pela CVM",
            description = "Retorna o snapshot completo do dump CVM filtrado por TIPO=CORRETORAS. Tipicamente ~150 corretoras."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de corretoras",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = CorretoraResponse.class)))),
            @ApiResponse(responseCode = "503",
                    description = "CVM indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<CorretoraResponse> listAllCorretoras() {
        return corretorasService.listAll();
    }

    @GetMapping(value = "/corretoras/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Detalhe de uma corretora por CNPJ",
            description = "Aceita CNPJ com ou sem máscara. Lookup feito sobre o snapshot cacheado — sem round-trip à CVM."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Corretora encontrada",
                    content = @Content(schema = @Schema(implementation = CorretoraResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "CNPJ inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "CNPJ não encontrado no snapshot",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "CVM indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CorretoraResponse findCorretoraByCnpj(
            @Parameter(description = "CNPJ com ou sem máscara (apenas dígitos serão considerados)",
                    example = "76621457000185", required = true)
            @PathVariable
            @Pattern(regexp = "[0-9./\\-]{14,18}",
                    message = "CNPJ deve conter 14 dígitos (com ou sem máscara).")
            String cnpj
    ) {
        return corretorasService.findByCnpj(cnpj);
    }

    @GetMapping(value = "/fundos", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar fundos CVM (paginado)",
            description = "Retorna sumários de fundos paginados. Tamanho máximo de página: 200 (proteção contra response gigante — o snapshot completo tem ~30k fundos)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Página de fundos",
                    content = @Content(schema = @Schema(implementation = FundosPageResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "page ou size fora dos limites",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "CVM indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public FundosPageResponse listFundos(
            @Parameter(description = "Página (1-based)", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Tamanho da página (máx 200)", example = "100")
            @RequestParam(defaultValue = "100") int size
    ) {
        return fundosService.listPaginated(page, size);
    }

    @GetMapping(value = "/fundos/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Detalhe completo de um fundo por CNPJ",
            description = "Retorna ~40 campos do cadastro CVM: composição, taxas, gestão, custódia, datas. Lookup sobre snapshot cacheado."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Fundo encontrado",
                    content = @Content(schema = @Schema(implementation = FundoDetailResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "CNPJ inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "CNPJ não encontrado no snapshot",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "CVM indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public FundoDetailResponse findFundoByCnpj(
            @Parameter(description = "CNPJ com ou sem máscara",
                    example = "00000000000000", required = true)
            @PathVariable
            @Pattern(regexp = "[0-9./\\-]{14,18}",
                    message = "CNPJ deve conter 14 dígitos (com ou sem máscara).")
            String cnpj
    ) {
        return fundosService.findByCnpj(cnpj);
    }
}
