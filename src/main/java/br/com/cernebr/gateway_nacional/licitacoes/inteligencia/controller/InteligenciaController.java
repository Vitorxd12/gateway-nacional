package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.controller;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.FiltroProspeccao;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto.ProspeccaoPage;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.service.ProspeccaoQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * API de Inteligência de Mercado (M3) — motor de prospecção B2B consumido pelo
 * CRM. Cruza participações homologadas (vencedores) com ramo/localização da
 * empresa e setor/cidade do edital.
 *
 * <p>Só é registrado quando o módulo está ligado
 * ({@code gateway.licitacoes.inteligencia.enabled=true}).</p>
 */
@RestController
@RequestMapping("/v1/inteligencia")
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
@Tag(name = "Inteligência de Licitações", description = "Cruzamento Licitações↔Empresas para prospecção B2B.")
public class InteligenciaController {

    private final ProspeccaoQueryService service;

    public InteligenciaController(ProspeccaoQueryService service) {
        this.service = service;
    }

    @GetMapping(value = "/prospeccao", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Prospecção de empresas por setor/localização/ramo",
            description = """
                    Retorna empresas (leads) que participaram de licitações no recorte filtrado,
                    agregadas com qtde de participações, valor total homologado e última data.

                    **Caso de uso CRM:** "empresas que venceram licitações do setor 'Educação' em
                    Aracaju" → `setor=EDUCACAO&municipioOrgao=2800308&papel=HOMOLOGADO`.

                    Filtros combinam a face do EDITAL (setor, cidade/UF do órgão, modalidade,
                    janela de data, faixa de valor, papel) e a face da EMPRESA (CNAE/ramo por
                    prefixo, UF, município). Todos opcionais e cumulativos.""")
    public ProspeccaoPage prospeccao(
            @Parameter(description = "Setor macro do edital.", example = "SAUDE")
            @RequestParam(required = false) String setor,
            @Parameter(description = "Código IBGE do município do órgão.", example = "2800308")
            @RequestParam(required = false) String municipioOrgao,
            @Parameter(description = "UF do órgão.", example = "SE")
            @RequestParam(required = false) String ufOrgao,
            @Parameter(description = "Modalidade (match parcial).", example = "Pregão")
            @RequestParam(required = false) String modalidade,
            @Parameter(description = "Papel da empresa.", example = "HOMOLOGADO")
            @RequestParam(required = false) String papel,
            @Parameter(description = "Data resultado de (ISO-8601 UTC).", example = "2026-04-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime dataDe,
            @Parameter(description = "Data resultado até (ISO-8601 UTC).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime dataAte,
            @Parameter(description = "Valor homologado mínimo (BRL).")
            @RequestParam(required = false) BigDecimal valorMin,
            @Parameter(description = "Valor homologado máximo (BRL).")
            @RequestParam(required = false) BigDecimal valorMax,
            @Parameter(description = "CNAE da empresa — prefixo (divisão/grupo/subclasse).", example = "4645")
            @RequestParam(required = false) String cnaeEmpresa,
            @Parameter(description = "UF da empresa.", example = "SE")
            @RequestParam(required = false) String ufEmpresa,
            @Parameter(description = "Código IBGE do município da empresa.")
            @RequestParam(required = false) String municipioEmpresa,
            @Parameter(description = "Página (0-based).", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamanho da página (máx 200).", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        FiltroProspeccao filtro = new FiltroProspeccao(
                setor, municipioOrgao, ufOrgao, modalidade, papel,
                dataDe, dataAte, valorMin, valorMax,
                cnaeEmpresa, ufEmpresa, municipioEmpresa, page, size);
        return service.prospectar(filtro);
    }
}
