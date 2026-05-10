package br.com.cernebr.gateway_nacional.cadastral.ibge.controller;

import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfDetailResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.service.IbgeService;
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
import java.util.Locale;

@Validated
@RestController
@RequestMapping("/api/v1/cadastral/ibge")
@Tag(
        name = "IBGE",
        description = "Dados geográficos oficiais do IBGE — UFs (com estimativa populacional) e municípios. Lista de municípios usa hedge entre IBGE oficial e DadosAbertosBR. Cache agressivo: 365d hard / 30d soft via RAC."
)
public class IbgeController {

    private final IbgeService ibgeService;

    public IbgeController(IbgeService ibgeService) {
        this.ibgeService = ibgeService;
    }

    @GetMapping(value = "/uf", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar todas as UFs do Brasil",
            description = "Retorna as 27 UFs com sigla, nome, código IBGE, região e capital. Cache 365d (RAC soft 30d)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de UFs",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = UfResponse.class)))),
            @ApiResponse(responseCode = "503",
                    description = "IBGE indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<UfResponse> listAllUfs() {
        return ibgeService.listAllUfs();
    }

    @GetMapping(value = "/uf/{codeOrSigla}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Detalhe de uma UF, com estimativa populacional",
            description = "Aceita sigla (2 letras, ex: SP) ou código IBGE numérico (ex: 35). Estimativa populacional é best-effort — se o agregado de população do IBGE estiver indisponível, o detalhe retorna sem os campos populacionais."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "UF encontrada",
                    content = @Content(schema = @Schema(implementation = UfDetailResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Sigla com formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "IBGE indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public UfDetailResponse findUfByCode(
            @Parameter(description = "Sigla (2 letras) ou código IBGE numérico", example = "SP", required = true)
            @PathVariable
            @Pattern(regexp = "[A-Za-z]{2}|\\d{1,2}",
                    message = "UF deve ser sigla de 2 letras (ex: SP) ou código IBGE numérico (ex: 35).")
            String codeOrSigla
    ) {
        return ibgeService.findUfByCode(canonicalize(codeOrSigla));
    }

    @GetMapping(value = "/uf/{sigla}/municipios", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar municípios de uma UF",
            description = "Hedge paralelo entre IBGE oficial (servicodados.ibge.gov.br) e DadosAbertosBR — vence o primeiro com sucesso. Resultado normalizado: nomes em UPPERCASE, ordenado por código IBGE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de municípios",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = MunicipioResponse.class)))),
            @ApiResponse(responseCode = "400",
                    description = "Sigla com formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "Ambos os providers de municípios indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<MunicipioResponse> listMunicipiosByUf(
            @Parameter(description = "Sigla da UF (2 letras)", example = "SP", required = true)
            @PathVariable
            @Pattern(regexp = "[A-Za-z]{2}",
                    message = "A sigla da UF deve ter exatamente 2 letras (ex: SP).")
            String sigla
    ) {
        return ibgeService.listMunicipiosByUf(sigla.toUpperCase(Locale.ROOT));
    }

    private static String canonicalize(String codeOrSigla) {
        // Normaliza sigla pra uppercase; deixa código numérico como está.
        return codeOrSigla.length() == 2 && !Character.isDigit(codeOrSigla.charAt(0))
                ? codeOrSigla.toUpperCase(Locale.ROOT)
                : codeOrSigla;
    }
}
