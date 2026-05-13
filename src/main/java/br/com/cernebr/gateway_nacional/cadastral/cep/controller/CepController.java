package br.com.cernebr.gateway_nacional.cadastral.cep.controller;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepBuscaResult;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.cadastral.cep.service.CepBuscaService;
import br.com.cernebr.gateway_nacional.cadastral.cep.service.CepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Validated
@RestController
@RequestMapping("/api/v1/cep")
@Tag(
        name = "CEP",
        description = "Consulta de endereços brasileiros a partir do Código de Endereçamento Postal, com fallback em cascata entre múltiplos provedores."
)
public class CepController {

    private final CepService cepService;
    private final CepBuscaService cepBuscaService;

    public CepController(CepService cepService, CepBuscaService cepBuscaService) {
        this.cepService = cepService;
        this.cepBuscaService = cepBuscaService;
    }

    // ── 1. Consulta por CEP (existente) ────────────────────────────────────────

    @GetMapping(value = "/{cep}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar endereço por CEP",
            description = "Resolve o CEP consultando ViaCEP, BrasilAPI e AwesomeAPI em cascata. O resultado é cacheado em Redis pelo CEP."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Endereço encontrado",
                    content = @Content(schema = @Schema(implementation = CepResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "CEP em formato inválido (esperado 8 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CepResponse findByCep(
            @Parameter(description = "CEP com 8 dígitos numéricos, sem hífen", example = "01001000", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{8}", message = "O CEP deve conter exatamente 8 dígitos numéricos, sem hífen.")
            String cep
    ) {
        return cepService.findByCep(cep);
    }

    // ── 2. Busca por endereço ──────────────────────────────────────────────────

    @GetMapping(value = "/busca", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Buscar CEPs por endereço (UF + cidade + logradouro)",
            description = """
                    Consulta o ViaCEP buscando CEPs a partir do endereço textual informado.
                    Retorna uma lista de candidatos — útil para autocompletar campos de endereço
                    ou confirmar o CEP de uma rua antes de cadastrá-lo.

                    **Limites do upstream (ViaCEP):**
                    - O logradouro deve ter no mínimo 3 caracteres.
                    - O resultado é limitado a 50 registros pelo ViaCEP.
                    - UF deve estar em caixa alta (ex.: SP, RJ, MG).
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de CEPs candidatos (pode ser vazia se não encontrou resultado)",
                    content = @Content(schema = @Schema(implementation = CepBuscaResult.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parâmetros inválidos (UF em formato incorreto, logradouro muito curto, etc.)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "O ViaCEP está indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CepBuscaResult buscarPorEndereco(
            @Parameter(description = "Sigla da UF em caixa alta", example = "SP", required = true)
            @RequestParam
            @NotBlank
            @Pattern(regexp = "[A-Z]{2}", message = "UF deve ser a sigla com 2 letras maiúsculas (ex.: SP, RJ).")
            String uf,

            @Parameter(description = "Nome do município", example = "São Paulo", required = true)
            @RequestParam
            @NotBlank
            @Size(min = 2, message = "O nome da cidade deve ter ao menos 2 caracteres.")
            String cidade,

            @Parameter(description = "Nome do logradouro (rua, avenida, praça etc.)", example = "Praça da Sé", required = true)
            @RequestParam
            @NotBlank
            @Size(min = 3, message = "O logradouro deve ter ao menos 3 caracteres.")
            String logradouro
    ) {
        return cepBuscaService.buscarPorEndereco(uf, cidade, logradouro);
    }

    // ── 3. Geocodificação reversa ──────────────────────────────────────────────

    @GetMapping(value = "/reverso", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Obter CEP a partir de coordenadas geográficas (clique no mapa)",
            description = """
                    Realiza geocodificação reversa via Nominatim/OpenStreetMap:
                    dado um par de coordenadas WGS84 (latitude e longitude), retorna
                    o endereço brasileiro e o CEP correspondentes ao ponto.

                    **Caso de uso principal:** o usuário clica num mapa (Leaflet, Google Maps,
                    Mapbox, etc.) e a aplicação envia as coordenadas para este endpoint para
                    obter o CEP sem que o usuário precise digitar nada.

                    **Comportamento quando não há resultado:**
                    - Se o ponto estiver em área sem CEP cadastrado (zona rural, oceano, rodovia),
                      a lista de candidatos virá vazia (`total: 0`).
                    - Não há erro 404 — o contrato usa lista vazia para diferenciar de falha de infra.

                    **Precisão:** o campo `localizacao.precisao` será sempre `EXATA` porque as
                    coordenadas fornecidas pelo caller já são o ponto exato clicado no mapa.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Endereço e CEP encontrados (ou lista vazia se não há CEP para o ponto)",
                    content = @Content(schema = @Schema(implementation = CepBuscaResult.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Coordenadas fora do intervalo válido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "O Nominatim está indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CepBuscaResult buscarPorCoordenadas(
            @Parameter(
                    description = "Latitude WGS84 em graus decimais (positivo = Norte, negativo = Sul)",
                    example = "-23.5505",
                    required = true
            )
            @RequestParam
            @DecimalMin(value = "-90.0", message = "Latitude deve ser >= -90.")
            @DecimalMax(value = "90.0",  message = "Latitude deve ser <= 90.")
            BigDecimal lat,

            @Parameter(
                    description = "Longitude WGS84 em graus decimais (positivo = Leste, negativo = Oeste)",
                    example = "-46.6333",
                    required = true
            )
            @RequestParam
            @DecimalMin(value = "-180.0", message = "Longitude deve ser >= -180.")
            @DecimalMax(value = "180.0",  message = "Longitude deve ser <= 180.")
            BigDecimal lon
    ) {
        return cepBuscaService.buscarPorCoordenadas(lat, lon);
    }
}
