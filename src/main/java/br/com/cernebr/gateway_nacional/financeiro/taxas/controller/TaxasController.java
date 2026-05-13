package br.com.cernebr.gateway_nacional.financeiro.taxas.controller;

import br.com.cernebr.gateway_nacional.financeiro.taxas.dto.TaxaResponse;
import br.com.cernebr.gateway_nacional.financeiro.taxas.service.TaxasService;
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
@RequestMapping("/api/v1/taxas")
@Tag(
        name = "Taxas",
        description = "Cotações dos principais índices financeiros brasileiros (CDI, Selic, IPCA), com fallback em cascata entre BrasilAPI, BCB SGS e HG Brasil."
)
public class TaxasController {

    private final TaxasService taxasService;

    public TaxasController(TaxasService taxasService) {
        this.taxasService = taxasService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar todas as taxas financeiras consolidadas",
            description = """
                    Retorna de uma só vez CDI, Selic e IPCA em um array canônico. \
                    Ideal para dashboards financeiros e aplicações que exibem os três \
                    indicadores simultaneamente sem precisar de 3 roundtrips.

                    Cada taxa é resolvida via cascata **BrasilAPI → BCB SGS → HG Brasil** \
                    e o array resultante é cacheado em Redis por **12 horas** com chave única \
                    `taxas::ALL` — uma única entrada cobre toda a listagem.

                    **Resiliência parcial:** se uma taxa falhar em todos os providers, \
                    ela é simplesmente omitida do array (nunca causa 503 se as demais \
                    estiverem saudáveis)."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Array com as taxas disponíveis (CDI, Selic, IPCA)",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaxaResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<TaxaResponse> listAll() {
        return taxasService.listAll();
    }

    @GetMapping(value = "/{sigla}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar a cotação atual de um índice financeiro",
            description = """
                    Retorna o valor mais recente publicado para o índice solicitado. \
                    Aceita as siglas `cdi`, `selic` e `ipca` (case-insensitive). \
                    Ordem da cascata: **BrasilAPI → BCB SGS → HG Brasil**. \
                    O resultado é cacheado em Redis pela sigla normalizada (uppercase) por 12 horas, \
                    coerente com a frequência diária de publicação.

                    **Nota operacional:** o terciário (HG Brasil) cobre apenas Selic e CDI. \
                    Quando o IPCA é solicitado, ele só será respondido se BrasilAPI ou BCB SGS \
                    estiverem disponíveis."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Cotação resolvida com sucesso",
                    content = @Content(schema = @Schema(implementation = TaxaResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Sigla inválida (apenas cdi, selic ou ipca são aceitas)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public TaxaResponse findBySigla(
            @Parameter(description = "Sigla do índice financeiro (case-insensitive)", example = "cdi", required = true,
                    schema = @Schema(allowableValues = {"cdi", "selic", "ipca"}))
            @PathVariable
            @Pattern(regexp = "cdi|selic|ipca",
                    flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "A sigla deve ser uma de: cdi, selic, ipca.")
            String sigla
    ) {
        return taxasService.findBySigla(sigla);
    }
}
