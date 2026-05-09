package br.com.cernebr.gateway_nacional.financeiro.cambio.controller;

import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioResponse;
import br.com.cernebr.gateway_nacional.financeiro.cambio.service.CambioService;
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
@RequestMapping("/api/v1/financeiro/cambio")
@Tag(
        name = "Câmbio",
        description = "Cotação de moedas em tempo (quase) real consumida da AwesomeAPI, com cache estratégico de 3 minutos para preservar a quota upstream."
)
public class CambioController {

    private final CambioService cambioService;

    public CambioController(CambioService cambioService) {
        this.cambioService = cambioService;
    }

    @GetMapping(value = "/{pares}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar a cotação atual de um ou mais pares de moedas",
            description = """
                    Retorna a cotação mais recente publicada pela AwesomeAPI para os pares informados. \
                    Aceita uma lista separada por vírgula no formato `MOEDA_ORIGEM-MOEDA_DESTINO`, por exemplo:

                    - `USD-BRL` — Dólar americano em Real
                    - `USD-BRL,EUR-BRL` — múltiplos pares em uma única requisição
                    - `BTC-BRL,ETH-BRL,USD-BRL` — combina criptomoedas e moedas fiat

                    A resposta é uma lista, mesmo quando apenas um par é solicitado.

                    **Cache:** as respostas ficam cacheadas por 3 minutos no Redis usando uma chave \
                    normalizada (uppercase + ordem alfabética), de modo que `USD-BRL,EUR-BRL` e \
                    `eur-brl,usd-brl` compartilham o mesmo registro. Isso colapsa rajadas de \
                    requisições simultâneas (dashboards, ERPs) em uma única chamada upstream a \
                    cada 3 minutos por combinação de pares."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Cotações resolvidas com sucesso",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = CambioResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Formato inválido (esperado MOEDA-MOEDA[,MOEDA-MOEDA...])",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Nenhum dos pares informados foi localizado na AwesomeAPI",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "AwesomeAPI indisponível (Circuit Breaker aberto ou erro de rede)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<CambioResponse> consultar(
            @Parameter(
                    description = "Lista de pares separados por vírgula no formato MOEDA-MOEDA",
                    example = "USD-BRL,EUR-BRL",
                    required = true
            )
            @PathVariable
            @Pattern(
                    regexp = "[A-Za-z]{3,4}-[A-Za-z]{3,4}(,[A-Za-z]{3,4}-[A-Za-z]{3,4})*",
                    message = "Os pares devem seguir o formato MOEDA-MOEDA (ex: USD-BRL) separados por vírgula."
            )
            String pares
    ) {
        return cambioService.consultar(pares).cotacoes();
    }
}
