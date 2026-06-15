package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.controller;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.LicitacaoParticipadaDTO;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.ParticipanteDTO;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.service.MapeamentoQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mapeamento bidirecional licitação↔empresa (M3). Lookups diretos por chave:
 * dado um edital → participantes; dado um CNPJ → histórico de participações.
 *
 * <p>Conditional ao flag do módulo. Caminhos full por método (convivem com o
 * {@code LicitacaoController} da federação sob o mesmo prefixo {@code /v1}).</p>
 */
@RestController
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
@Tag(name = "Inteligência de Licitações", description = "Mapeamento bidirecional Licitações↔Empresas.")
public class MapeamentoController {

    private final MapeamentoQueryService service;

    public MapeamentoController(MapeamentoQueryService service) {
        this.service = service;
    }

    @GetMapping(value = "/v1/licitacoes/{portal}/{identificador}/empresas", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Empresas participantes de uma licitação",
            description = "Lista as empresas (participantes/vencedoras) de um edital — uma linha por item participado.")
    public List<ParticipanteDTO> empresasDaLicitacao(
            @Parameter(description = "Slug do portal.", example = "comprasnet") @PathVariable String portal,
            @Parameter(description = "Identificador {cnpjOrgao}-{ano}-{seq}.", example = "00508903000188-2026-685")
            @PathVariable String identificador) {
        return service.empresasDaLicitacao(portal, identificador);
    }

    @GetMapping(value = "/v1/empresas/{cnpj}/licitacoes", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Histórico de participações de uma empresa",
            description = "Lista as licitações em que o CNPJ participou (uma linha por edital, itens agregados).")
    public List<LicitacaoParticipadaDTO> licitacoesDaEmpresa(
            @Parameter(description = "CNPJ (com ou sem máscara).", example = "02566043000164")
            @PathVariable String cnpj) {
        return service.licitacoesDaEmpresa(cnpj);
    }
}
