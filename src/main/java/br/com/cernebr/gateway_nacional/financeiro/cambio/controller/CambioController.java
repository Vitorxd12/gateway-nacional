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
        description = "Cotação de moedas com prioridade para PTAX oficial (Banco Central). Cascata em duas camadas: PTAX hedge entre BrasilAPI e BCB OLINDA — fallback transparente para AwesomeAPI quando o par não é PTAX-elegível (cripto, cross-currency sem BRL) ou ambos providers oficiais estão indisponíveis."
)
public class CambioController {

    private final CambioService cambioService;

    public CambioController(CambioService cambioService) {
        this.cambioService = cambioService;
    }

    @GetMapping(value = "/{pares}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar a cotação atual de um ou mais pares de moedas — PTAX oficial quando disponível",
            description = """
                    Retorna a cotação mais recente para os pares informados, com **prioridade para o PTAX oficial \
                    do Banco Central** (regulatório, fiscal, contábil) e fallback transparente para AwesomeAPI \
                    (spot real-time comercial) quando o PTAX não pode atender.

                    **Aceita uma lista separada por vírgula no formato `MOEDA_ORIGEM-MOEDA_DESTINO`:**

                    - `USD-BRL` — Dólar americano em Real → resolvido por **PTAX**
                    - `USD-BRL,EUR-BRL` — múltiplos pares em uma única requisição → **PTAX** para ambos
                    - `BTC-BRL,USD-BRL` — combina cripto e fiat → **AwesomeAPI** (PTAX não cobre cripto)
                    - `USD-EUR` — cross-currency sem BRL → **AwesomeAPI** (PTAX só publica vs BRL)

                    **Como saber qual fonte respondeu:** PTAX é fixing diário do BCB, então `dataHoraAtualizacao` \
                    reflete a data da publicação oficial (geralmente D-1) e `variacao` vem `null` (PTAX não \
                    publica variação intra-dia). Respostas via AwesomeAPI vêm com timestamp atual e `variacao` preenchida.

                    **Cobertura PTAX (BCB):** USD, EUR, GBP, JPY, CHF, CAD, AUD, DKK, NOK, SEK, ARS, MXN, TRY, ZAR, CNY, HKD.

                    **Cache:** chave normalizada (uppercase + ordem alfabética estável), TTL 3 minutos. \
                    `USD-BRL,EUR-BRL` e `eur-brl,usd-brl` compartilham o mesmo registro Redis."""
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
                    description = "Nenhum dos pares informados foi localizado em nenhum tier",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Tanto PTAX (BrasilAPI/BCB OLINDA) quanto AwesomeAPI estão indisponíveis",
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
