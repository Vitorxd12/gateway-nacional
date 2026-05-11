package br.com.cernebr.gateway_nacional.saude.tuss.controller;

import br.com.cernebr.gateway_nacional.saude.tuss.dto.TussCodigoResponse;
import br.com.cernebr.gateway_nacional.saude.tuss.dto.TussPageResponse;
import br.com.cernebr.gateway_nacional.saude.tuss.service.TussService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/saude/tuss")
@Tag(
        name = "Saúde — TUSS",
        description = "Dicionário oficial TUSS (Terminologia Unificada da Saúde Suplementar) publicado pela ANS. Cascata BrasilAPI (proxy oficial) → snapshot local in-memory para resiliência total."
)
public class TussController {

    private final TussService tussService;

    public TussController(TussService tussService) {
        this.tussService = tussService;
    }

    @GetMapping(value = "/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar código TUSS por valor exato",
            description = """
                    Retorna o procedimento ANS associado ao código TUSS. \
                    Aceita 8 dígitos canônicos sem separadores.

                    **Engine de Resiliência:**
                    - **Tier 1:** BrasilAPI {@code /api/tuss/v1/{tuss}} — proxy \
                      oficial do dicionário ANS.
                    - **Tier 2:** snapshot local embarcado em \
                      {@code data/tuss_terms.json} (~24k códigos). Acionado \
                      automaticamente se o Tier 1 estiver indisponível.

                    **Cache:** {@code tuss} hard-TTL 7d (cadência típica de \
                    revisão da ANS)."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Código TUSS encontrado",
                    content = @Content(schema = @Schema(implementation = TussCodigoResponse.class))),
            @ApiResponse(responseCode = "404", description = "Código não consta no dicionário ANS",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TussCodigoResponse detalhe(
            @Parameter(description = "Código TUSS (8 dígitos numéricos)", example = "10101012", required = true)
            @PathVariable
            @Pattern(regexp = "^[0-9]{6,10}$", message = "Código TUSS deve conter 6 a 10 dígitos numéricos.")
            String codigo
    ) {
        return tussService.findByCodigo(codigo);
    }

    @GetMapping(value = "/autocomplete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Autocomplete (typeahead) leve para campos de busca",
            description = """
                    Endpoint dedicado para typeahead em UIs de prontuário/faturamento \
                    hospitalar — campo "busque o procedimento" digitado letra a letra, \
                    onde N keystrokes geram N requests. Diferente de \
                    {@code GET /api/v1/saude/tuss} (que devolve a página completa com \
                    envelope total/limit/offset), aqui o payload é um **array plano** \
                    {@code [{tuss, nome}, ...]} com no máximo 20 entradas — \
                    sub-50ms ponta-a-ponta no caminho feliz.

                    **Engine de Resiliência:**
                    - **Tier 1:** BrasilAPI {@code /api/tuss/v1/autocomplete} \
                      (match='prefix' + sort='tuss asc' forçados upstream).
                    - **Tier 2:** snapshot local in-memory — predicado idêntico \
                      replicado em Java, com **early-exit** ao atingir o limit \
                      (evita varrer 24k entradas).

                    **Cache de borda:** resposta sai com \
                    {@code Cache-Control: public, max-age=300, stale-while-revalidate=60}. \
                    Em vez de um Redis Cache do lado servidor (que adicionaria \
                    round-trip pra rota que já é sub-milissegundo), o gateway \
                    delega o cache para o browser/CDN — keystrokes repetidos do \
                    mesmo usuário (ex.: usuário apaga uma letra e digita de novo) \
                    nem chegam à origem.

                    **Combinação de filtros:** {@code q}, {@code name} e \
                    {@code tuss} são todos AND. {@code q} aceita múltiplos tokens \
                    separados por espaço (cada token é roteado individualmente: \
                    dígitos viram prefixo de código, texto vira contains no nome). \
                    Útil para "consulta hospitalar" → casa entradas com AMBAS as \
                    palavras."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Array com até {limit} resultados leves",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = TussCodigoResponse.class))))
    })
    public ResponseEntity<List<TussCodigoResponse>> autocomplete(
            @Parameter(description = "Query livre — pode conter texto e/ou dígitos misturados (cada token é AND).",
                    example = "consulta")
            @RequestParam(name = "q", required = false) String q,
            @Parameter(description = "Filtro adicional só por nome (case-insensitive, sem acentuação).")
            @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "Filtro adicional só por prefixo de código.")
            @RequestParam(name = "tuss", required = false)
            @Pattern(regexp = "^[0-9]{1,10}$", message = "Prefixo TUSS deve conter apenas dígitos.")
            String tuss,
            @Parameter(description = "Tamanho máximo da página (1 a 20). Default 10.", example = "10")
            @RequestParam(name = "limit", required = false, defaultValue = "10")
            @Min(1) @Max(20) int limit
    ) {
        List<TussCodigoResponse> resultados = tussService.autocomplete(q, name, tuss, limit);
        // Cache-Control de borda: 5 minutos no browser/CDN com janela
        // stale-while-revalidate. Para typeahead, isso significa que o
        // SEGUNDO keystroke "co" → "cons" não bate na origem se o usuário
        // estiver corrigindo a digitação dele mesmo.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))
                        .cachePublic()
                        .staleWhileRevalidate(Duration.ofSeconds(60)))
                .body(resultados);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Buscar/listar códigos TUSS",
            description = """
                    Lista o dicionário TUSS com filtros opcionais por nome \
                    (busca parcial case-insensitive) e/ou prefixo de código. \
                    Suporta paginação via {@code limit}/{@code offset}.

                    **Engine de Resiliência:**
                    - **Tier 1:** BrasilAPI {@code /api/tuss/v1} — paginação \
                      e filtros nativos.
                    - **Tier 2:** snapshot local — paginação aplicada em \
                      memória sobre o índice de ~24k entradas.

                    Sem filtros, devolve a primeira página do dicionário inteiro."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de resultados",
                    content = @Content(schema = @Schema(implementation = TussPageResponse.class)))
    })
    public TussPageResponse buscar(
            @Parameter(description = "Substring do nome do procedimento (case-insensitive, sem acentuação).")
            @RequestParam(name = "name", required = false) String name,

            @Parameter(description = "Prefixo do código TUSS para filtrar por família.")
            @RequestParam(name = "tuss", required = false)
            @Pattern(regexp = "^[0-9]{1,10}$", message = "Prefixo TUSS deve conter apenas dígitos.")
            String tuss,

            @Parameter(description = "Tamanho da página (1 a 500). Omitir devolve toda a lista filtrada.")
            @RequestParam(name = "limit", required = false)
            @Min(1) @Max(500) Integer limit,

            @Parameter(description = "Deslocamento (offset) para paginação. Default 0.")
            @RequestParam(name = "offset", required = false)
            @Min(0) Integer offset
    ) {
        return tussService.search(name, tuss, limit, offset);
    }
}
