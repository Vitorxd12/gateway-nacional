package br.com.cernebr.gateway_nacional.cadastral.cnpj.controller;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.service.CnpjConsolidadoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoint consolidado de CNPJ.
 *
 * <p>Aceita entrada com ou sem máscara — a sanitização é feita no boundary
 * antes do service. A resposta inclui o header {@code X-CNPJ-Sources}
 * listando os providers sobreviventes para auditoria de SLA.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/cadastral/cnpj")
@Tag(name = "CNPJ Consolidado",
        description = "Engine canônica de CNPJ — agrega Consulta Básica + QSA + Empresa em uma " +
                "única resposta a partir de múltiplos providers gratuitos com fail-soft.")
public class CnpjConsolidadoController {

    private final CnpjConsolidadoService service;

    public CnpjConsolidadoController(CnpjConsolidadoService service) {
        this.service = service;
    }

    @GetMapping(value = "/{cnpj}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar CNPJ consolidado (Básica + QSA + Empresa)",
            description = "Dispara em paralelo 5 providers (CnpjWs, OpenCnpj, CnpjA, ReceitaWS, " +
                    "Archive CNPJ.PW), aplica timeout duro de 10s por provider e devolve o merge " +
                    "canônico dos sobreviventes. Capital social sempre normalizado para reais. " +
                    "QSA limitado a 300 sócios. Empresário Individual (NJ 213-5) devolve QSA vazia."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Empresa encontrada",
                    content = @Content(schema = @Schema(implementation = CnpjConsolidadoDTO.class))),
            @ApiResponse(responseCode = "400", description = "CNPJ em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Todos os providers estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CnpjConsolidadoDTO> findByCnpj(
            @Parameter(description = "CNPJ com ou sem máscara (14 dígitos no total).",
                    example = "00.000.000/0001-91", required = true)
            @PathVariable
            @NotBlank
            String cnpj
    ) {
        String limpo = sanitize(cnpj);
        CnpjConsolidadoDTO consolidado = service.findByCnpj(limpo);
        return ResponseEntity.ok()
                .header("X-CNPJ-Sources", String.join(",", consolidado.fontesSobreviventes()))
                .header("X-CNPJ-Source-Count", String.valueOf(consolidado.fontesSobreviventes().size()))
                .body(consolidado);
    }

    private static String sanitize(String raw) {
        String onlyDigits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (onlyDigits.length() != 14) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CNPJ deve conter exatamente 14 dígitos (com ou sem máscara). Recebido: " + raw);
        }
        return onlyDigits;
    }
}
