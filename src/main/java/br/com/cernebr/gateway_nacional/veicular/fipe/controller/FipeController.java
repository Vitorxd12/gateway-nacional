package br.com.cernebr.gateway_nacional.veicular.fipe.controller;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeMarcaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTabelaReferenciaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTipoVeiculo;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeVeiculoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.service.FipeService;
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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Validated
@RestController
@RequestMapping("/api/v1/fipe")
@Tag(
        name = "FIPE",
        description = "Cotação e navegação na Tabela FIPE. Cotação: cascata FIPE-Oficial → BrasilAPI → Parallelum. Navegação (marcas, veículos, tabelas): cascata FIPE-Oficial → BrasilAPI."
)
public class FipeController {

    private static final String CODIGO_FIPE_REGEX = "^[0-9]{6}-[0-9]{1}$";
    private static final String ANO_MODELO_REGEX = "\\d{4,5}";

    private final FipeService fipeService;

    public FipeController(FipeService fipeService) {
        this.fipeService = fipeService;
    }

    @GetMapping(value = "/preco/{codigoFipe}/{anoModelo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar preço FIPE de um veículo",
            description = """
                    Retorna a cotação FIPE referente ao código e ano modelo informados. \
                    Aceita o ano calendário com 4 dígitos (ex: `2024`) ou o sentinela \
                    `32000` para indicar Zero KM, conforme convenção FIPE.

                    Ordem da cascata: **BrasilAPI → Parallelum**. O resultado é cacheado \
                    em Redis por 15 dias (FIPE publica atualizações mensalmente; o TTL \
                    garante que o cliente veja o novo mês-de-referência logo após cada \
                    ciclo de publicação)."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Cotação resolvida com sucesso",
                    content = @Content(schema = @Schema(implementation = FipePrecoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código FIPE inválido (esperado padrão 000000-0) ou ano em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public FipePrecoResponse findPreco(
            @Parameter(description = "Código FIPE no padrão 000000-0", example = "005340-0", required = true)
            @PathVariable
            @Pattern(regexp = CODIGO_FIPE_REGEX,
                    message = "O código FIPE deve seguir o padrão 000000-0 (6 dígitos, hífen, 1 dígito).")
            String codigoFipe,

            @Parameter(description = "Ano modelo (4 dígitos) ou 32000 para Zero KM", example = "2018", required = true)
            @PathVariable
            @Pattern(regexp = ANO_MODELO_REGEX,
                    message = "O ano modelo deve conter 4 dígitos (ex: 2024) ou ser 32000 para Zero KM.")
            String anoModelo
    ) {
        return fipeService.findPreco(codigoFipe, anoModelo);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navegação FIPE — descoberta de códigos pra usar em /preco/{codigoFipe}.
    // Sem essas 3 rotas, o cliente precisaria de uma fonte externa só pra
    // saber qual é o codigoFipe de "Gol Total Flex 2018".
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping(value = "/marcas/{tipo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar marcas/montadoras de um tipo de veículo",
            description = "Aceita tipos: carros, motos, caminhoes (todos lowercase). Use o {valor} retornado como {codigoMarca} em /veiculos/{tipo}/{codigoMarca}. Aceita ?tabelaReferencia=N pra histórico."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de marcas",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = FipeMarcaResponse.class)))),
            @ApiResponse(responseCode = "404",
                    description = "Tipo de veículo inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "Todos os providers de FIPE estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<FipeMarcaResponse> listMarcas(
            @Parameter(description = "Tipo de veículo: carros, motos, caminhoes", example = "carros", required = true)
            @PathVariable String tipo,
            @Parameter(description = "Tabela-de-referência opcional (se omitido, usa a mais recente)", example = "333")
            @RequestParam(name = "tabelaReferencia", required = false) Integer tabelaReferencia
    ) {
        return fipeService.listMarcas(resolveTipo(tipo), tabelaReferencia);
    }

    @GetMapping(value = "/veiculos/{tipo}/{codigoMarca}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar modelos de uma marca",
            description = "Use o {valor} de /marcas/{tipo} como {codigoMarca} aqui. Retorna a lista de modelos disponíveis. Quando resolvido pelo fallback BrasilAPI, o campo {valor} de cada modelo vem null (BrasilAPI dropa o ID interno)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de modelos",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = FipeVeiculoResponse.class)))),
            @ApiResponse(responseCode = "404",
                    description = "Tipo de veículo inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "Todos os providers de FIPE estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<FipeVeiculoResponse> listVeiculos(
            @Parameter(description = "Tipo de veículo: carros, motos, caminhoes", example = "carros", required = true)
            @PathVariable String tipo,
            @Parameter(description = "Código da marca (valor retornado por /marcas/{tipo})", example = "59", required = true)
            @PathVariable String codigoMarca,
            @Parameter(description = "Tabela-de-referência opcional", example = "333")
            @RequestParam(name = "tabelaReferencia", required = false) Integer tabelaReferencia
    ) {
        return fipeService.listVeiculosByMarca(resolveTipo(tipo), codigoMarca, tabelaReferencia);
    }

    @GetMapping(value = "/tabelas", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar tabelas-de-referência FIPE disponíveis",
            description = "Uma tabela-de-referência por mês de publicação, mais recente primeiro. Use o {codigo} em ?tabelaReferencia=N nas rotas de marcas/veiculos pra recuperar histórico."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lista de tabelas-de-referência",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = FipeTabelaReferenciaResponse.class)))),
            @ApiResponse(responseCode = "503",
                    description = "Todos os providers de FIPE estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<FipeTabelaReferenciaResponse> listTabelas() {
        return fipeService.listTabelasReferencia();
    }

    private static FipeTipoVeiculo resolveTipo(String raw) {
        return FipeTipoVeiculo.fromWireValue(raw)
                .orElseThrow(() -> new ResourceNotFoundException("FipeTipoVeiculo",
                        "Tipo de veículo inválido: '" + raw + "'. Aceitos: " + supportedTipos()));
    }

    private static String supportedTipos() {
        return Arrays.stream(FipeTipoVeiculo.values())
                .map(FipeTipoVeiculo::wireValue)
                .collect(Collectors.joining(", "));
    }
}
