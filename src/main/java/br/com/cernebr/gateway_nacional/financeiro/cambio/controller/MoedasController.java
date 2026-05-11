package br.com.cernebr.gateway_nacional.financeiro.cambio.controller;

import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.MoedaResponse;
import br.com.cernebr.gateway_nacional.financeiro.cambio.service.MoedasCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/financeiro/cambio")
@Tag(
        name = "Câmbio — Moedas",
        description = "Catálogo dinâmico de moedas publicadas pelo Banco Central via PTAX. Hedge entre BCB OLINDA (fonte canônica) e BrasilAPI (proxy) — o primeiro a responder vence. Útil para listar quais pares são PTAX-elegíveis antes de chamar /cambio/{pares}."
)
public class MoedasController {

    private final MoedasCatalogService moedasService;

    public MoedasController(MoedasCatalogService moedasService) {
        this.moedasService = moedasService;
    }

    @GetMapping(value = "/moedas", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar o catálogo dinâmico de moedas PTAX (BCB)",
            description = """
                    Devolve a lista completa de moedas que o Banco Central publica no \
                    endpoint OData {@code /odata/Moedas} — a mesma fonte canônica que \
                    o Gateway consome internamente para validar pares antes de \
                    consultar cotações em {@code /cambio/{pares}}.

                    **Engine de resiliência:**
                    - **Tier 1:** hedge paralelo entre BCB OLINDA direto e BrasilAPI \
                      (proxy do mesmo endpoint). Vence o primeiro a responder.
                    - **Cache:** {@code ptaxCatalog} hard-TTL 30d (chave {@code 'detail'} \
                      separada do {@code 'all'} usado pela validação de câmbio).
                    - O catálogo BCB muda ~uma vez por ano — TTL longo absorve \
                      instabilidades transitórias sem desync perceptível.

                    Cada item devolvido pode ser usado como {@code MOEDA_ORIGEM} no \
                    endpoint de cotações: {@code GET /api/v1/financeiro/cambio/USD-BRL}."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Catálogo resolvido com sucesso",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = MoedaResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "BCB OLINDA e BrasilAPI indisponíveis simultaneamente",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<MoedaResponse> listar() {
        return moedasService.listAll();
    }
}
