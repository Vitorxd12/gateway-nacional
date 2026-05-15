package br.com.cernebr.gateway_nacional.veicular.avaliacao.controller;

import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.KbbRouteDTO;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.service.KbbDiscoveryService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Endpoint de auditoria do Discovery Layer KBB — expõe a rota canônica
 * {@code (slug, kbbId)} resolvida pelo {@link KbbDiscoveryService} para um
 * dado par {@code (codigoFipe, anoModelo)} sem disparar a raspagem de
 * preços. Útil para validar, antes de uma avaliação completa, se o portal
 * KBB tem mapeamento para o veículo — e qual URL exata será raspada.
 *
 * <p>Diferentemente da rota {@code /api/v1/avaliacao/manual}, este endpoint
 * <b>não</b> passa pelo {@code RefreshAheadCache} envelopado em torno do
 * {@code KbbScraperClient}. Cada requisição chama o Discovery diretamente —
 * o que torna observáveis nos logs as transições L1 CACHE HIT / L1 CACHE
 * MISS / DYNAMIC OK / DYNAMIC MISS.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/avaliacao/kbb-discovery")
@Tag(
        name = "Avaliação · KBB Discovery",
        description = "Auditoria do mapeamento dinâmico FIPE → KBB. Resolve a rota canônica (slug + KbbId) consultando primeiro o índice L1, depois o motor de busca interno da KBB via FlareSolverr."
)
public class KbbDiscoveryController {

    private static final String CODIGO_FIPE_REGEX = "^[0-9]{6}-[0-9]{1}$";

    private final KbbDiscoveryService discoveryService;

    public KbbDiscoveryController(KbbDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Resolver rota KBB para um veículo FIPE + ano",
            description = """
                    Resolve o trio (slugMarca, slugModelo, slugVersao) + kbbId que o portal \
                    `kbb.com.br` exige para servir o blob `vehiclePrices`. A resolução é \
                    em cascata: L1 (índice em memória — seed + runtime) → L3 (busca \
                    dinâmica via FlareSolverr/Jsoup no catálogo da marca/modelo no portal).

                    **Quando usar:** auditoria/diagnóstico antes de uma avaliação \
                    completa, debugging de "veículo não mapeado", catalogação de rotas \
                    descobertas para promoção a SEED.

                    **Status HTTP:** 200 com o `KbbRouteDTO` quando a rota foi resolvida; \
                    404 quando nem L1 nem a busca dinâmica encontraram mapeamento."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Rota canônica resolvida com sucesso",
                    content = @Content(schema = @Schema(implementation = KbbRouteDTO.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Veículo não mapeado nem em L1 nem via busca dinâmica",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parâmetros inválidos (formato FIPE, ano fora de faixa, marca/modelo em branco)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public ResponseEntity<?> resolverRota(
            @Parameter(description = "Código FIPE do veículo (padrão 000000-0).",
                    example = "004275-7", required = true)
            @RequestParam
            @Pattern(regexp = CODIGO_FIPE_REGEX,
                    message = "O código FIPE deve seguir o padrão 000000-0 (6 dígitos, hífen, 1 dígito).")
            String codigoFipe,

            @Parameter(description = "Ano modelo (4 dígitos).", example = "2018", required = true)
            @RequestParam
            @Min(value = 1900, message = "O ano deve ser maior ou igual a 1900.")
            @Max(value = 2100, message = "O ano deve ser menor ou igual a 2100.")
            int ano,

            @Parameter(description = "Dica de marca para a busca dinâmica.",
                    example = "Chevrolet", required = true)
            @RequestParam
            @NotBlank(message = "A marca é obrigatória para a busca dinâmica.")
            String marca,

            @Parameter(description = "Dica de modelo para a busca dinâmica.",
                    example = "Onix", required = true)
            @RequestParam
            @NotBlank(message = "O modelo é obrigatório para a busca dinâmica.")
            String modelo
    ) {
        Optional<KbbRouteDTO> route = discoveryService.discover(codigoFipe, marca, modelo, ano);
        if (route.isPresent()) {
            return ResponseEntity.ok(route.get());
        }
        ProblemDetail problem = ProblemDetail.forStatus(404);
        problem.setTitle("KBB Discovery: rota não encontrada");
        problem.setDetail("Discovery Layer não resolveu rota canônica para FIPE=" + codigoFipe
                + " ano=" + ano + ". Veículo provavelmente não está catalogado no portal KBB.");
        problem.setProperty("fipe", codigoFipe);
        problem.setProperty("ano", ano);
        problem.setProperty("indiceTamanho", discoveryService.size());
        return ResponseEntity.status(404).body(problem);
    }

    @GetMapping(value = "/index", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Snapshot do índice L1 do Discovery Layer",
            description = """
                    Devolve o conteúdo atual do índice em memória — todas as rotas \
                    resolvidas (SEED do classpath + RUNTIME re-hidratadas do disco + \
                    DYNAMIC descobertas em runtime). Útil para inspecionar o estado do \
                    cache sem precisar tail do log."""
    )
    public Map<String, KbbRouteDTO> snapshot() {
        return discoveryService.snapshot();
    }
}
