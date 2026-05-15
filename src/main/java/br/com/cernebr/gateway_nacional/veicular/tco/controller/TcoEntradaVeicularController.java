package br.com.cernebr.gateway_nacional.veicular.tco.controller;

import br.com.cernebr.gateway_nacional.veicular.tco.dto.TcoEntradaVeicularDTO;
import br.com.cernebr.gateway_nacional.veicular.tco.service.TcoEntradaVeicularService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/**
 * Endpoint cross-domain de "Custos Estaduais e TCO" — entrega o Custo Total
 * de Entrada de um veículo cruzando a cotação FIPE com a malha fiscal
 * estadual (IPVA + taxa de transferência do Detran).
 */
@Validated
@RestController
@RequestMapping("/api/v1/veicular/precificacao")
@Tag(
        name = "TCO Veicular",
        description = "Custo Total de Entrada: cruza a cotação FIPE com a alíquota de IPVA e a taxa de transferência da UF informada."
)
public class TcoEntradaVeicularController {

    private static final String CODIGO_FIPE_REGEX = "^[0-9]{6}-[0-9]{1}$";
    private static final String UF_REGEX = "^[A-Za-z]{2}$";
    private static final String ANO_MODELO_REGEX = "\\d{4,5}";

    private final TcoEntradaVeicularService tcoService;

    public TcoEntradaVeicularController(TcoEntradaVeicularService tcoService) {
        this.tcoService = tcoService;
    }

    @GetMapping(value = "/{fipeCode}/custos-entrada", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Calcular o Custo Total de Entrada (TCO) de um veículo por UF",
            description = """
                    Cruza o domínio Veicular (cotação FIPE) com o domínio Fiscal/Cadastral \
                    estadual para entregar o **Custo Total de Entrada** de um veículo.

                    O motor consome o valor exato da Tabela FIPE, multiplica pela alíquota \
                    oficial de IPVA da UF informada para obter a `estimativaIpvaAnual`, e soma \
                    a estimativa da Taxa de Transferência de Propriedade do Detran local para \
                    cravar o `custoTotalEntrada`.

                    **Motor de Alíquotas em Memória:** as alíquotas de IPVA e taxas de \
                    transferência ficam num snapshot estático carregado no startup \
                    (`data/ipva_aliquotas_uf.json`) — zero dependência de rede. Se a UF não \
                    estiver mapeada, aplica-se a alíquota modal nacional de 3% como fallback \
                    resiliente e o campo `fallbackAplicado` retorna `true`.

                    Se `anoModelo` for omitido, o serviço elege automaticamente o ano-modelo \
                    mais recente do histórico FIPE do código. Resultado cacheado em Redis por \
                    30 dias (chave composta `fipeCode + uf + anoModelo`)."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Custo Total de Entrada calculado com sucesso",
                    content = @Content(schema = @Schema(implementation = TcoEntradaVeicularDTO.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código FIPE, UF ou ano modelo em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores de FIPE estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public TcoEntradaVeicularDTO calcularCustosEntrada(
            @Parameter(description = "Código FIPE no padrão 000000-0", example = "005340-0", required = true)
            @PathVariable
            @Pattern(regexp = CODIGO_FIPE_REGEX,
                    message = "O código FIPE deve seguir o padrão 000000-0 (6 dígitos, hífen, 1 dígito).")
            String fipeCode,

            @Parameter(description = "Sigla da UF para o cálculo fiscal (2 letras)", example = "SP", required = true)
            @RequestParam("uf")
            @Pattern(regexp = UF_REGEX, message = "A UF deve conter exatamente 2 letras (ex: SP, RJ, MG).")
            String uf,

            @Parameter(description = "Ano modelo opcional (4 dígitos ou 32000 p/ Zero KM). Se omitido, usa o ano mais recente do histórico FIPE.", example = "2018")
            @RequestParam(name = "anoModelo", required = false)
            @Pattern(regexp = ANO_MODELO_REGEX,
                    message = "O ano modelo deve conter 4 dígitos (ex: 2024) ou ser 32000 para Zero KM.")
            String anoModelo
    ) {
        return tcoService.calcular(fipeCode, uf, anoModelo);
    }
}
