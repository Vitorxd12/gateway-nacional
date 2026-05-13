package br.com.cernebr.gateway_nacional.fiscal.ncm.controller;

import br.com.cernebr.gateway_nacional.fiscal.ncm.dto.NcmResponse;
import br.com.cernebr.gateway_nacional.fiscal.ncm.service.NcmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fiscal/ncm")
@Tag(
        name = "Fiscal — NCM",
        description = "Consulta da Nomenclatura Comum do Mercosul, com fallback em cascata BrasilAPI → Siscomex e cache de 30 dias."
)
public class NcmController {

    /**
     * Aceita 8 dígitos puros ({@code 33051000}) ou pontuado ({@code 3305.10.00}).
     * O serviço normaliza ao chamar o upstream — mantemos a validação leve
     * aqui pra não rejeitar formatos que o BrasilAPI aceita nativamente.
     */
    private static final String NCM_REGEX = "^[0-9]{2}(?:\\.?[0-9]{2}){0,2}\\.?[0-9]{0,2}$";

    private final NcmService ncmService;

    public NcmController(NcmService ncmService) {
        this.ncmService = ncmService;
    }

    @GetMapping(value = "/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar NCM por código exato",
            description = """
                    Retorna a entrada NCM correspondente ao código informado. Aceita o \
                    código com ou sem pontos (`3305.10.00` e `33051000` são equivalentes).

                    Ordem da cascata: **BrasilAPI → Siscomex**. O resultado é cacheado em \
                    Redis por **30 dias** — a tabela do Mercosul muda algumas vezes por \
                    ano via resoluções da Camex, então um TTL longo virtualmente zera o \
                    tráfego upstream sem risco real de drift.

                    Quando o código não consta em **nenhum** provedor (resposta \
                    determinística e consistente), o gateway responde **404**. Quando \
                    todos os provedores estão indisponíveis (sem resposta determinística), \
                    a resposta é **503** — distinção semântica que orienta o consumidor \
                    a parar (404) ou retentar com backoff (503)."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "NCM resolvido com sucesso",
                    content = @Content(schema = @Schema(implementation = NcmResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Código NCM em formato inválido (esperado 8 dígitos com ou sem pontos)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Código NCM não consta no catálogo Mercosul",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public NcmResponse findByCodigo(
            @Parameter(description = "Código NCM (8 dígitos, com ou sem pontos)", example = "33051000", required = true)
            @PathVariable
            @Pattern(regexp = NCM_REGEX,
                    message = "O código NCM deve conter 8 dígitos, com ou sem pontos (ex: 33051000 ou 3305.10.00).")
            String codigo
    ) {
        return ncmService.findByCodigo(codigo);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar catálogo NCM completo ou buscar por descrição",
            description = """
                    **Sem parâmetros** — retorna o catálogo completo de NCMs (todos os ~15k itens \
                    da Nomenclatura Comum do Mercosul). Fonte primária: dump oficial do Portal Único \
                    Siscomex atualizado diariamente. Cache Redis de **30 dias** (hard-TTL) com \
                    chave `ncm::catalogo-completo`.

                    **Com `?descricao={texto}`** — pesquisa textual sobre as descrições oficiais. \
                    Cascata **BrasilAPI → Siscomex**. Cache Redis de **30 dias** com chave \
                    normalizada em lowercase.

                    Útil para emissão de NF-e, validação fiscal automatizada e carga inicial \
                    de tabelas de referência em ERPs."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Catálogo completo (sem params) ou lista filtrada (com ?descricao)",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = NcmResponse.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parâmetro `descricao` presente mas em branco ou muito curto",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Todos os provedores externos estão indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public List<NcmResponse> listOrSearch(
            @Parameter(description = "Texto livre para busca (omita para catálogo completo)", example = "leite")
            @RequestParam(required = false)
            @Size(min = 2, max = 80, message = "A descrição deve ter entre 2 e 80 caracteres.")
            String descricao
    ) {
        if (descricao == null || descricao.isBlank()) {
            return ncmService.listAll();
        }
        return ncmService.searchByDescricao(descricao);
    }
}
