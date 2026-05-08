package br.com.cernebr.gateway_nacional.cadastral.cnae.controller;

import br.com.cernebr.gateway_nacional.cadastral.cnae.dto.CnaeResponse;
import br.com.cernebr.gateway_nacional.cadastral.cnae.service.CnaeService;
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
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/cadastral/cnae")
@Tag(
        name = "Cadastral — CNAE",
        description = "Consulta da Classificação Nacional de Atividades Econômicas, com fallback em cascata IBGE → snapshot local e cache de 30 dias."
)
public class CnaeController {

    /**
     * Aceita estritamente 7 dígitos sem separadores ({@code 6422100}).
     * Diferente do NCM, não tolerar pontuação aqui — a Receita Federal
     * usa o formato sem separadores em todas as integrações com CNPJ,
     * que é o caso de uso primário (consumir CNAE devolvido pelo módulo
     * CNPJ deste mesmo gateway).
     */
    private static final String CNAE_REGEX = "^[0-9]{7}$";

    private final CnaeService cnaeService;

    public CnaeController(CnaeService cnaeService) {
        this.cnaeService = cnaeService;
    }

    @GetMapping(value = "/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar CNAE por código (subclasse)",
            description = """
                    Retorna a descrição oficial CONCLA da subclasse CNAE informada \
                    (formato 7 dígitos, mesmo formato que vem em \
                    `cnaePrincipal` na resposta de `/api/v1/cnpj/{cnpj}`).

                    Ordem da cascata: **IBGE → Snapshot local**. O IBGE \
                    (`servicodados.ibge.gov.br`) é a fonte canônica; o snapshot \
                    local é um JSON bake-into-image (~118 KB / 1.332 subclasses) \
                    que garante operação mesmo durante outage do IBGE.

                    Resultado é cacheado em Redis por **30 dias** — a tabela CNAE \
                    é virtualmente estática, atualizada algumas vezes por ano via \
                    Resoluções CONCLA.

                    Quando o código não existe em nenhum provedor (resposta \
                    determinística), retorna **404**. Quando todos os provedores \
                    estão indisponíveis (sem resposta confiável), retorna **503**."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "CNAE resolvido com sucesso",
                    content = @Content(schema = @Schema(implementation = CnaeResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código CNAE em formato inválido (esperado: 7 dígitos sem separadores)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Código CNAE não consta na tabela CONCLA",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public CnaeResponse findByCodigo(
            @Parameter(description = "Código CNAE subclasse (7 dígitos, sem separadores)",
                    example = "6422100", required = true)
            @PathVariable
            @Pattern(regexp = CNAE_REGEX,
                    message = "O código CNAE deve conter exatamente 7 dígitos numéricos (ex: 6422100).")
            String codigo
    ) {
        return cnaeService.findByCodigo(codigo);
    }
}
