package br.com.cernebr.gateway_nacional.operacional.cptec.controller;

import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CidadeCptecResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CondicaoAtualResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.OndasResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.PrevisaoClimaResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.service.CptecService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/operacional/cptec")
@Tag(
        name = "CPTEC — Meteorologia (Operacional / Agro / Logística)",
        description = "Previsão, condições atuais e ondas do CPTEC/INPE para apoio a operações logísticas, portuárias e agroindustriais. Hedge paralelo entre INPE direto (XML legado) e BrasilAPI (proxy)."
)
public class CptecController {

    private final CptecService cptecService;

    public CptecController(CptecService cptecService) {
        this.cptecService = cptecService;
    }

    @GetMapping(value = "/cidade/{nome}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Buscar cidades CPTEC por nome",
            description = """
                    Devolve a lista de cidades cujo nome contém o termo informado. \
                    Cada item traz o {@code id} numérico exigido pelas rotas de \
                    previsão e ondas — sem ele as demais rotas não conseguem \
                    consultar o INPE."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cidades encontradas",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = CidadeCptecResponse.class)))),
            @ApiResponse(responseCode = "503",
                    description = "INPE e BrasilAPI indisponíveis simultaneamente",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<CidadeCptecResponse> cidades(
            @Parameter(description = "Trecho ou nome da cidade (mínimo 2 caracteres)", example = "São Paulo")
            @PathVariable @NotBlank String nome
    ) {
        return cptecService.searchCidades(nome);
    }

    @GetMapping(value = "/clima/capital", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Condições METAR atuais para todas as capitais",
            description = "Snapshot mais recente publicado pelo CPTEC para cada uma das 27 capitais brasileiras.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = CondicaoAtualResponse.class)))),
            @ApiResponse(responseCode = "503", description = "Indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<CondicaoAtualResponse> capitais() {
        return cptecService.condicoesCapitais();
    }

    @GetMapping(value = "/clima/aeroporto/{icao}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Condições METAR atuais para um aeroporto por código ICAO",
            description = "Útil para janelas de pouso/decolagem, planejamento de rota de carga aérea e operações 24h.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = CondicaoAtualResponse.class))),
            @ApiResponse(responseCode = "503", description = "Indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CondicaoAtualResponse aeroporto(
            @Parameter(description = "Código ICAO de 4 letras", example = "SBGR")
            @PathVariable
            @Pattern(regexp = "^[A-Za-z]{4}$", message = "Código ICAO deve conter 4 letras.")
            String icao
    ) {
        return cptecService.condicoesAeroporto(icao);
    }

    @GetMapping(value = "/clima/previsao/{cityCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Previsão climática para uma cidade",
            description = "Retorna até 6 dias de previsão (limite do upstream CPTEC). Use ?dias=N para reduzir a janela.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = PrevisaoClimaResponse.class))),
            @ApiResponse(responseCode = "503", description = "Indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PrevisaoClimaResponse previsao(
            @Parameter(description = "Código numérico da cidade (use /cidade/{nome} para descobrir)", example = "244")
            @PathVariable int cityCode,
            @Parameter(description = "Janela de previsão em dias (1 a 6)", example = "3")
            @RequestParam(name = "dias", defaultValue = "3") @Min(1) @Max(6) int dias
    ) {
        return cptecService.previsao(cityCode, dias);
    }

    @GetMapping(value = "/clima/previsao/semana/{lat}/{lon}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Previsão climática semanal por coordenadas (lat/long)",
            description = """
                    Devolve a previsão de até 6 dias para o ponto geográfico \
                    informado — sem exigir lookup prévio de {@code cityCode}. \
                    Caso de uso típico: frota com telemetria GPS, drones agro, \
                    fazendas com coordenadas fixas, ERPs de logística que \
                    cruzam latitude/longitude de hubs ou entregas.

                    O CPTEC/INPE resolve internamente a estação meteorológica \
                    mais próxima e devolve a previsão dessa cidade. Pontos no \
                    mar aberto ou fora do território brasileiro retornam 503 \
                    (cidade não localizada)."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Previsão resolvida",
                    content = @Content(schema = @Schema(implementation = PrevisaoClimaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Latitude/longitude fora dos limites válidos",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "INPE e BrasilAPI indisponíveis simultaneamente",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PrevisaoClimaResponse previsaoSemana(
            @Parameter(description = "Latitude em graus decimais (WGS84). Brasil: aproximadamente -33 a 5.", example = "-23.5505")
            @PathVariable
            @DecimalMin(value = "-90.0", message = "Latitude deve ser ≥ -90.")
            @DecimalMax(value = "90.0", message = "Latitude deve ser ≤ 90.")
            double lat,
            @Parameter(description = "Longitude em graus decimais (WGS84). Brasil: aproximadamente -75 a -34.", example = "-46.6333")
            @PathVariable
            @DecimalMin(value = "-180.0", message = "Longitude deve ser ≥ -180.")
            @DecimalMax(value = "180.0", message = "Longitude deve ser ≤ 180.")
            double lon,
            @Parameter(description = "Janela de previsão em dias (1 a 6)", example = "6")
            @RequestParam(name = "dias", defaultValue = "6") @Min(1) @Max(6) int dias
    ) {
        return cptecService.previsaoSemana(lat, lon, dias);
    }

    @GetMapping(value = "/ondas/{cityCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Previsão de ondas e vento marítimo",
            description = "Boletim agrupado por dia, com vento, direção e altura de onda — alimenta planejamento portuário e logística costeira.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = OndasResponse.class))),
            @ApiResponse(responseCode = "503", description = "Indisponível",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public OndasResponse ondas(
            @Parameter(description = "Código numérico da cidade (litorânea)", example = "241")
            @PathVariable int cityCode,
            @Parameter(description = "Janela de previsão em dias (1 a 6)", example = "3")
            @RequestParam(name = "dias", defaultValue = "3") @Min(1) @Max(6) int dias
    ) {
        return cptecService.ondas(cityCode, dias);
    }
}
