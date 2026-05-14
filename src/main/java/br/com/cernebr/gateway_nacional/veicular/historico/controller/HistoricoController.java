package br.com.cernebr.gateway_nacional.veicular.historico.controller;

import br.com.cernebr.gateway_nacional.veicular.historico.dto.HistoricoVeicularDTO;
import br.com.cernebr.gateway_nacional.veicular.historico.service.HistoricoService;
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

import java.util.Locale;

@Validated
@RestController
@RequestMapping("/api/v1/historico")
@Tag(
        name = "Histórico Veicular",
        description = "Consulta gratuita do histórico veicular: indícios de leilão e sinistro coletados em paralelo (Virtual Threads) sobre fontes públicas + FlareSolverr para contornar Cloudflare. Fail-soft — fontes que falham são desligadas do agregado sem derrubar a resposta."
)
public class HistoricoController {

    private static final String PLACA_REGEX = "^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$";

    private final HistoricoService historicoService;

    public HistoricoController(HistoricoService historicoService) {
        this.historicoService = historicoService;
    }

    @GetMapping(value = "/{placa}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Histórico consolidado: indícios de leilão e sinistro",
            description = """
                    Orquestra um fan-out paralelo (Virtual Threads) sobre as fontes \
                    gratuitas configuradas:

                    1. **LeilaoFree** — scraping de `leilaofree.com.br`;
                    2. **ConsultarPlaca** — scraping de `consultar-placa.com` \
                       (alternativa configurável: `portalods.com.br`);
                    3. **PlacaFipe (baseline)** — registro veicular para garantir \
                       trilho de auditoria com dados reais extraídos do DOM mesmo \
                       quando as duas fontes anteriores estão indisponíveis.

                    Todas as fontes passam pelo **FlareSolverr** quando o sidecar \
                    está configurado, contornando Cloudflare/JS-Challenge.

                    **Risco consolidado:**
                    - `BAIXO` — nada consta nas fontes sobreviventes;
                    - `MEDIO` — exatamente um indício (leilão OU sinistro);
                    - `ALTO`  — leilão E sinistro simultâneos.

                    **Fail-Soft:** se uma fonte falhar (timeout, CB aberto, layout \
                    drift), ela é desligada de `fontesConsultadas` e a resposta segue \
                    com o resultado das demais. Apenas quando *todas* as fontes \
                    falham o array `fontesConsultadas` vem vazio — ainda assim com \
                    HTTP 200, pois o veredito "consulta inconclusiva" é informação \
                    útil para o cliente.

                    **Cache:** soft-TTL 6h / hard-TTL 24h via `RefreshAheadCache` para \
                    proteger as fontes frágeis de bursts repetidos sobre a mesma placa."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Histórico consolidado (lista de fontes pode estar vazia se todas falharem).",
                    content = @Content(schema = @Schema(implementation = HistoricoVeicularDTO.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Placa em formato inválido — não casa antigo (ABC1234) nem Mercosul (ABC1D23).",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public HistoricoVeicularDTO consultar(
            @Parameter(description = "Placa nos padrões antigo ou Mercosul, case-insensitive.",
                    example = "ABC1D23", required = true)
            @PathVariable
            @Pattern(regexp = PLACA_REGEX,
                    message = "A placa deve seguir o padrão antigo (ABC1234) ou Mercosul (ABC1D23).")
            String placa
    ) {
        String normalized = placa.toUpperCase(Locale.ROOT).replace("-", "");
        return historicoService.consultar(normalized);
    }
}
