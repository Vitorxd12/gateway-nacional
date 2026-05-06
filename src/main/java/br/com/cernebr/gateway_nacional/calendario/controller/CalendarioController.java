package br.com.cernebr.gateway_nacional.calendario.controller;

import br.com.cernebr.gateway_nacional.calendario.dto.FeriadoResponse;
import br.com.cernebr.gateway_nacional.calendario.dto.ProximoDiaUtilResponse;
import br.com.cernebr.gateway_nacional.calendario.service.CalendarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/calendario")
@Tag(
        name = "Calendário",
        description = "Feriados nacionais brasileiros e cálculo de dia útil, com fallback em cascata e calculador determinístico in-memory."
)
public class CalendarioController {

    private final CalendarioService calendarioService;

    public CalendarioController(CalendarioService calendarioService) {
        this.calendarioService = calendarioService;
    }

    @GetMapping(value = "/feriados/{ano}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar feriados nacionais por ano",
            description = "Retorna os feriados nacionais brasileiros para o ano informado. Ordem da cascata: BrasilAPI → Nager.Date → calculador in-memory (Meeus/Jones/Butcher). O resultado é cacheado em Redis pelo ano."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de feriados nacionais",
                    content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = FeriadoResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ano em formato inválido (esperado 4 dígitos numéricos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<FeriadoResponse> findFeriados(
            @Parameter(description = "Ano com 4 dígitos", example = "2025", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{4}", message = "O ano deve conter exatamente 4 dígitos numéricos.")
            String ano
    ) {
        return calendarioService.findByAno(Integer.parseInt(ano));
    }

    @GetMapping(value = "/proximo-dia-util", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Calcular o próximo dia útil",
            description = """
                    A partir de uma data-base, retorna a data correspondente ao próximo dia útil — \
                    pulando sábados, domingos e feriados nacionais. Quando a data-base já é um dia útil, \
                    ela mesma é devolvida.

                    **Aviso:** atualmente este endpoint considera apenas feriados **nacionais**. \
                    Feriados estaduais e municipais ainda não são contemplados — o suporte está planejado \
                    para uma próxima versão e exigirá informar a UF (ou código IBGE do município) na consulta."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Próximo dia útil resolvido",
                    content = @Content(schema = @Schema(implementation = ProximoDiaUtilResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parâmetro 'data' ausente ou em formato inválido (esperado yyyy-MM-dd)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public ProximoDiaUtilResponse findProximoDiaUtil(
            @Parameter(description = "Data-base no formato ISO yyyy-MM-dd", example = "2025-04-21", required = true)
            @RequestParam("data")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data
    ) {
        LocalDate proximo = calendarioService.calcularProximoDiaUtil(data);
        return new ProximoDiaUtilResponse(data, proximo, calendarioService.diasAdicionados(data, proximo));
    }
}
