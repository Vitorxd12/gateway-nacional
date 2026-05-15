package br.com.cernebr.gateway_nacional.veicular.avaliacao.controller;

import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.AvaliacaoCompletaResponse;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.service.AvaliacaoRiscoService;
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

import java.util.Locale;

@Validated
@RestController
@RequestMapping("/api/v1/veicular")
@Tag(
        name = "Avaliação e Risco Integrado",
        description = "Motor de inteligência cross-domain: cruza a precificação de mercado (Placa + FIPE + scraping de marketplaces) com o histórico de risco (leilão + sinistro, premium-first com fallback gratuito) e aplica uma depreciação automática de 20%–30% quando há indício de risco."
)
public class AvaliacaoRiscoController {

    private static final String PLACA_REGEX = "^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$";
    private static final String CODIGO_FIPE_REGEX = "^[0-9]{6}-[0-9]{1}$";
    private static final String UF_REGEX = "^[A-Za-z]{2}$";

    private final AvaliacaoRiscoService avaliacaoRiscoService;

    public AvaliacaoRiscoController(AvaliacaoRiscoService avaliacaoRiscoService) {
        this.avaliacaoRiscoService = avaliacaoRiscoService;
    }

    private static String normalizeUf(String uf) {
        if (uf == null || uf.isBlank()) {
            return null;
        }
        return uf.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCidade(String cidade) {
        if (cidade == null || cidade.isBlank()) {
            return null;
        }
        return cidade.trim();
    }

    @GetMapping(value = "/placa/{placa}/avaliacao-completa", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Avaliação completa com depreciação automática por risco",
            description = """
                    Compõe, em uma única resposta, o cruzamento de dois domínios \
                    rodando concorrentemente em Virtual Threads:

                    1. **Precificação** — cascata de placa (WDApi → Keplaca → \
                       PlacaFipe), referência FIPE e raspagem paralela de \
                       marketplaces (OLX, MobiAuto) para o preço médio de mercado;
                    2. **Histórico de risco** — orquestração híbrida: se \
                       `GATEWAY_OLHONOCARRO_KEY` / `GATEWAY_CHECKAUTO_KEY` \
                       estiverem configuradas, o gateway consome as APIs \
                       premium oficiais em milissegundos; sem chave, a malha \
                       de scrapers gratuitos (LeilaoFree, ConsultarPlaca, \
                       PlacaFipe baseline + FlareSolverr) assume como fallback \
                       de resiliência.

                    **Motor de depreciação automática:**
                    - `BAIXO` (nada consta) — preço de mercado mantido integral, \
                      `precoAjustadoRisco == precoMedioOriginal`;
                    - `MEDIO` (leilão OU sinistro) — redutor de 20% sobre o \
                      preço base;
                    - `ALTO` (leilão E sinistro) — redutor de 30% sobre o \
                      preço base.

                    O redutor é configurável via \
                    `gateway.veicular.risco.redutor-medio` / `redutor-alto`, \
                    sempre clampeado à faixa mandatória de 20%–30%. A base do \
                    redutor é o preço médio de mercado; quando os scrapers não \
                    retornam nada, cai para a referência FIPE.

                    **Fail-soft:** ambas as pernas degradam de forma controlada \
                    — scrapers de marketplace caem um a um, fontes de histórico \
                    que falham são desligadas de `fontesHistorico` — e a \
                    resposta segue 200 com o que sobreviveu."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Avaliação integrada computada — inclui preço original, preço ajustado por risco e o alerta de risco grave.",
                    content = @Content(schema = @Schema(implementation = AvaliacaoCompletaResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Placa em formato inválido ou codigoFipe fora do padrão 000000-0.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Cascata de placa falhou em todos os provedores — sem identificação não há avaliação.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public AvaliacaoCompletaResponse avaliacaoCompleta(
            @Parameter(description = "Placa nos padrões antigo ou Mercosul, case-insensitive.",
                    example = "ABC1D23", required = true)
            @PathVariable
            @Pattern(regexp = PLACA_REGEX,
                    message = "A placa deve seguir o padrão antigo (ABC1234) ou Mercosul (ABC1D23).")
            String placa,

            @Parameter(description = "Código FIPE do veículo (padrão 000000-0). Opcional — quando ausente, a referência FIPE é omitida e o redutor cai sobre o preço de mercado.",
                    example = "005340-0")
            @RequestParam(required = false)
            @Pattern(regexp = CODIGO_FIPE_REGEX,
                    message = "O código FIPE deve seguir o padrão 000000-0 (6 dígitos, hífen, 1 dígito).")
            String codigoFipe,

            @Parameter(description = "UF para regionalizar a raspagem de mercado. Opcional — sem UF a busca degrada graciosamente para escopo nacional.",
                    example = "SP")
            @RequestParam(required = false)
            @Pattern(regexp = UF_REGEX, message = "A UF deve ter exatamente 2 letras.")
            String uf,

            @Parameter(description = "Cidade para estreitar a raspagem dentro da UF. Opcional — só é aplicada quando `uf` também é informada.",
                    example = "Campinas")
            @RequestParam(required = false)
            String cidade
    ) {
        String normalizedPlaca = placa.toUpperCase(Locale.ROOT).replace("-", "");
        return avaliacaoRiscoService.avaliarComRisco(
                normalizedPlaca, codigoFipe, normalizeUf(uf), normalizeCidade(cidade));
    }
}
