package br.com.cernebr.gateway_nacional.veicular.avaliacao.controller;

import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.AvaliacaoResponse;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.service.AvaliacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/avaliacao")
@Tag(
        name = "Avaliação",
        description = "Avaliação cruzada de veículos: identificação por placa + cotação FIPE + raspagem em tempo real de marketplaces (OLX, MobiAuto)."
)
public class AvaliacaoController {

    private static final String PLACA_REGEX = "^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$";
    private static final String CODIGO_FIPE_REGEX = "^[0-9]{6}-[0-9]{1}$";
    private static final String UF_REGEX = "^[A-Za-z]{2}$";

    private final AvaliacaoService avaliacaoService;

    public AvaliacaoController(AvaliacaoService avaliacaoService) {
        this.avaliacaoService = avaliacaoService;
    }

    /** Uppercased UF, or {@code null} when blank — keeps the national fallback explicit. */
    private static String normalizeUf(String uf) {
        if (uf == null || uf.isBlank()) {
            return null;
        }
        return uf.trim().toUpperCase(Locale.ROOT);
    }

    /** Trimmed city, or {@code null} when blank. */
    private static String normalizeCidade(String cidade) {
        if (cidade == null || cidade.isBlank()) {
            return null;
        }
        return cidade.trim();
    }

    @GetMapping(value = "/placa/{placa}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Avaliar veículo por placa cruzando FIPE e mercado real",
            description = """
                    Compõe três fontes em uma única resposta:
                    1. **Identificação** via cascata de placa (WDApi → Keplaca);
                    2. **Referência FIPE** quando o `codigoFipe` é informado (a vinculação \
                       placa → código FIPE não é resolvida pelo gateway, então o cliente \
                       deve passá-la);
                    3. **Mercado real** via raspagem paralela (Virtual Threads) nos \
                       marketplaces configurados (OLX, MobiAuto). A média é comparada \
                       contra a FIPE com tolerância de ±5% para gerar o score.

                    **Recorte geográfico:** informe `uf` (e opcionalmente `cidade`) para \
                    regionalizar a raspagem — cada marketplace reescreve a URL alvo no seu \
                    próprio padrão (OLX usa subdomínio de estado, MobiAuto usa query params). \
                    Sem `uf`, a busca degrada graciosamente para o escopo nacional.

                    **Privacidade:** o chassi devolvido em `dadosVeiculo` permanece \
                    mascarado, conforme contrato do módulo Placa.

                    **Resiliência da raspagem:** seletores CSS de marketplaces são \
                    voláteis. Cada scraper tem seu próprio Circuit Breaker e parses \
                    isolados — quando um site muda o HTML, a raspagem falha de forma \
                    controlada e o veredito é calculado sobre os scrapers restantes."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Avaliação computada com sucesso (mercado pode estar vazio se todos os scrapers falharem)",
                    content = @Content(schema = @Schema(implementation = AvaliacaoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Placa em formato inválido ou codigoFipe fora do padrão 000000-0",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Cascata de placa falhou em todos os provedores",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public AvaliacaoResponse avaliarPorPlaca(
            @Parameter(description = "Placa nos padrões antigo ou Mercosul, case-insensitive",
                    example = "ABC1D23", required = true)
            @PathVariable
            @Pattern(regexp = PLACA_REGEX,
                    message = "A placa deve seguir o padrão antigo (ABC1234) ou Mercosul (ABC1D23).")
            String placa,

            @Parameter(description = "Código FIPE do veículo (padrão 000000-0). Opcional — quando ausente, a referência FIPE é omitida e o score reflete a falta.",
                    example = "005340-0")
            @RequestParam(required = false)
            @Pattern(regexp = CODIGO_FIPE_REGEX,
                    message = "O código FIPE deve seguir o padrão 000000-0 (6 dígitos, hífen, 1 dígito).")
            String codigoFipe,

            @Parameter(description = "UF para regionalizar a raspagem de mercado (sigla de 2 letras). Opcional — ausente faz a busca nacional.",
                    example = "SP")
            @RequestParam(required = false)
            @Pattern(regexp = UF_REGEX, message = "A UF deve ser uma sigla de 2 letras (ex: SP, AM).")
            String uf,

            @Parameter(description = "Cidade para estreitar a raspagem dentro da UF. Opcional — só é aplicada quando `uf` também é informada.",
                    example = "Campinas")
            @RequestParam(required = false)
            String cidade
    ) {
        String normalizedPlaca = placa.toUpperCase(Locale.ROOT).replace("-", "");
        return avaliacaoService.avaliarPorPlaca(
                normalizedPlaca, codigoFipe, normalizeUf(uf), normalizeCidade(cidade));
    }

    @GetMapping(value = "/manual", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Avaliação manual — direto FIPE + mercado, sem consulta de placa",
            description = """
                    Rota livre de tokens de placa. Pula totalmente o módulo Placa \
                    (WDApi/Keplaca) e vai direto à FIPE (opcional) e aos scrapers de \
                    marketplaces. **Use quando**:

                    - você já sabe marca, modelo e ano do veículo (ex: avaliação de \
                      anúncio que você está prestes a publicar);
                    - os provedores de placa estão bloqueados, sem credencial ou \
                      momentaneamente fora;
                    - você quer eliminar o custo de uma chamada externa que não \
                      agrega informação ao seu caso de uso.

                    **Garantias mantidas**: a raspagem segue paralela em Virtual Threads, \
                    cada scraper protegido por seu próprio Circuit Breaker; falha de um \
                    marketplace não derruba os demais; cálculo de score com tolerância \
                    de ±5% sobre a FIPE quando o `codigoFipe` é fornecido.

                    **Recorte geográfico**: `uf` e `cidade` regionalizam a raspagem do \
                    mesmo jeito da rota por placa — ausentes, a busca é nacional.

                    Na resposta, `placa` e `dadosVeiculo` chegam **null** — sinalização \
                    explícita de que a identificação por placa foi pulada."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Avaliação computada com sucesso (mercado pode estar vazio se todos os scrapers falharem)",
                    content = @Content(schema = @Schema(implementation = AvaliacaoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parâmetros obrigatórios ausentes, ano fora de faixa ou codigoFipe fora do padrão 000000-0",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public AvaliacaoResponse avaliarPorVeiculo(
            @Parameter(description = "Marca do veículo (livre, será slugificada para compor URLs de busca)",
                    example = "Volkswagen", required = true)
            @RequestParam
            @NotBlank(message = "A marca é obrigatória.")
            String marca,

            @Parameter(description = "Modelo / versão do veículo (livre, será slugificada para compor URLs de busca)",
                    example = "Gol 1.0 Flex", required = true)
            @RequestParam
            @NotBlank(message = "O modelo é obrigatório.")
            String modelo,

            @Parameter(description = "Ano modelo (4 dígitos)",
                    example = "2011", required = true)
            @RequestParam
            @Min(value = 1900, message = "O ano deve ser maior ou igual a 1900.")
            @Max(value = 2100, message = "O ano deve ser menor ou igual a 2100.")
            int ano,

            @Parameter(description = "Código FIPE no padrão 000000-0 (opcional). Quando ausente, a referência FIPE é omitida.",
                    example = "005340-0")
            @RequestParam(required = false)
            @Pattern(regexp = CODIGO_FIPE_REGEX,
                    message = "O código FIPE deve seguir o padrão 000000-0 (6 dígitos, hífen, 1 dígito).")
            String codigoFipe,

            @Parameter(description = "UF para regionalizar a raspagem de mercado (sigla de 2 letras). Opcional — ausente faz a busca nacional.",
                    example = "SP")
            @RequestParam(required = false)
            @Pattern(regexp = UF_REGEX, message = "A UF deve ser uma sigla de 2 letras (ex: SP, AM).")
            String uf,

            @Parameter(description = "Cidade para estreitar a raspagem dentro da UF. Opcional — só é aplicada quando `uf` também é informada.",
                    example = "Campinas")
            @RequestParam(required = false)
            String cidade
    ) {
        return avaliacaoService.avaliarPorVeiculo(
                marca, modelo, ano, codigoFipe, normalizeUf(uf), normalizeCidade(cidade));
    }
}
