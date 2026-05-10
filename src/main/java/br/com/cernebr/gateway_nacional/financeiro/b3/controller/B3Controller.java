package br.com.cernebr.gateway_nacional.financeiro.b3.controller;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTickerResponse;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3FundoTipo;
import br.com.cernebr.gateway_nacional.financeiro.b3.dto.B3StockTickerResponse;
import br.com.cernebr.gateway_nacional.financeiro.b3.service.B3TickersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/financeiro/b3")
@Tag(
        name = "B3 Tickers",
        description = "Listagem de tickers de ações e fundos da B3. Snapshot completo baixado via paginação paralela e cacheado com RAC (soft 7d / hard 30d). Single provider (B3 é a fonte canônica)."
)
public class B3Controller {

    private final B3TickersService service;

    public B3Controller(B3TickersService service) {
        this.service = service;
    }

    @GetMapping(value = "/acoes", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar todas as ações listadas na B3",
            description = "Retorna o snapshot completo das ações (~600 emissoras). Primeira chamada após expiração do cache leva ~3-5s pra agregar todas as páginas em paralelo; chamadas subsequentes vêm de cache instantâneo."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de ações",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = B3StockTickerResponse.class)))),
            @ApiResponse(responseCode = "503",
                    description = "B3 indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<B3StockTickerResponse> listAcoes() {
        return service.listAcoes();
    }

    @GetMapping(value = "/fundos/{tipo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar tickers de fundos B3 por tipo",
            description = "Tipos aceitos: FII, SETORIAL, FIAGRO-FII, FIAGRO-FIDC, FIAGRO-FIP, FIP, FIA. Aceita underscore no lugar de hífen (FIAGRO_FII = FIAGRO-FII)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de fundos do tipo solicitado",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = B3FundoTickerResponse.class)))),
            @ApiResponse(responseCode = "404",
                    description = "Tipo de fundo inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "B3 indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<B3FundoTickerResponse> listFundos(
            @Parameter(description = "Tipo de fundo. Valores: FII, SETORIAL, FIAGRO-FII, FIAGRO-FIDC, FIAGRO-FIP, FIP, FIA",
                    example = "FII", required = true)
            @PathVariable String tipo
    ) {
        B3FundoTipo resolvedTipo = B3FundoTipo.fromWireValue(tipo)
                .orElseThrow(() -> new ResourceNotFoundException("B3FundoTipo",
                        "Tipo de fundo inválido: '" + tipo + "'. Aceitos: " + supportedTipos()));
        return service.listFundosByTipo(resolvedTipo);
    }

    private static String supportedTipos() {
        return Arrays.stream(B3FundoTipo.values())
                .map(B3FundoTipo::wireValue)
                .collect(Collectors.joining(", "));
    }
}
