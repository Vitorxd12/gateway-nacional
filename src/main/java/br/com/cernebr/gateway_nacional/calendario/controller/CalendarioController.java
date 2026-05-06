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
import java.util.Locale;

@Validated
@RestController
@RequestMapping("/api/v1/calendario")
@Tag(
        name = "Calendário",
        description = "Feriados nacionais e estaduais brasileiros e cálculo de dia útil, com fallback em cascata e calculador determinístico in-memory."
)
public class CalendarioController {

    private static final String UF_REGEX = "[A-Za-z]{2}";

    private final CalendarioService calendarioService;

    public CalendarioController(CalendarioService calendarioService) {
        this.calendarioService = calendarioService;
    }

    @GetMapping(value = "/feriados/{ano}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar feriados por ano (e opcionalmente por UF)",
            description = """
                    Retorna os feriados brasileiros para o ano informado. Quando o parâmetro \
                    `siglaUf` é fornecido (ex: `SP`, `RJ`), o resultado inclui também os feriados \
                    estaduais daquela UF — caso contrário, apenas os nacionais.

                    Ordem da cascata: BrasilAPI → Nager.Date → calculador in-memory \
                    (Meeus/Jones/Butcher). **Nota operacional:** o suporte a feriados estaduais \
                    é exclusivo do BrasilAPI; quando os provedores secundários servem a resposta, \
                    apenas os feriados nacionais são entregues. O resultado é cacheado em Redis \
                    com chave composta `{ano}-{UF|BR}`."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de feriados",
                    content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = FeriadoResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ano em formato inválido (esperado 4 dígitos) ou UF inválida (esperado 2 letras)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<FeriadoResponse> findFeriados(
            @Parameter(description = "Ano com 4 dígitos", example = "2025", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{4}", message = "O ano deve conter exatamente 4 dígitos numéricos.")
            String ano,

            @Parameter(description = "Sigla da UF (2 letras). Quando omitida, retorna apenas feriados nacionais.", example = "SP")
            @RequestParam(required = false)
            @Pattern(regexp = UF_REGEX, message = "A UF deve conter exatamente 2 letras (ex: SP, RJ).")
            String siglaUf
    ) {
        return calendarioService.findByAno(Integer.parseInt(ano), normalizeUf(siglaUf));
    }

    @GetMapping(value = "/proximo-dia-util", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Calcular o próximo dia útil",
            description = """
                    A partir de uma data-base, retorna a data correspondente ao próximo dia útil — \
                    pulando sábados, domingos e feriados. Quando a data-base já é um dia útil, \
                    ela mesma é devolvida.

                    Quando o parâmetro `siglaUf` é informado (ex: `SP`), o cálculo também \
                    considera os feriados **estaduais** daquela UF, além dos nacionais. Sem a UF, \
                    apenas feriados nacionais são pulados."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Próximo dia útil resolvido",
                    content = @Content(schema = @Schema(implementation = ProximoDiaUtilResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parâmetro 'data' ausente/inválido (esperado yyyy-MM-dd) ou UF inválida",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public ProximoDiaUtilResponse findProximoDiaUtil(
            @Parameter(description = "Data-base no formato ISO yyyy-MM-dd", example = "2025-04-21", required = true)
            @RequestParam("data")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,

            @Parameter(description = "Sigla da UF (2 letras). Quando informada, considera também feriados estaduais.", example = "SP")
            @RequestParam(required = false)
            @Pattern(regexp = UF_REGEX, message = "A UF deve conter exatamente 2 letras (ex: SP, RJ).")
            String siglaUf
    ) {
        String normalizedUf = normalizeUf(siglaUf);
        LocalDate proximo = calendarioService.calcularProximoDiaUtil(data, normalizedUf);
        return new ProximoDiaUtilResponse(data, proximo, calendarioService.diasAdicionados(data, proximo));
    }

    /**
     * Normalizes the UF: trims, uppercases, and treats blank as {@code null}.
     * Ensures cache keys and upstream URLs are canonical regardless of how
     * the client casing or whitespace looks.
     */
    private static String normalizeUf(String siglaUf) {
        if (siglaUf == null || siglaUf.isBlank()) {
            return null;
        }
        return siglaUf.trim().toUpperCase(Locale.ROOT);
    }
}
