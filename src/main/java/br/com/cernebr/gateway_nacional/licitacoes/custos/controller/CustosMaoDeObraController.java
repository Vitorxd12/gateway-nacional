package br.com.cernebr.gateway_nacional.licitacoes.custos.controller;

import br.com.cernebr.gateway_nacional.licitacoes.custos.dto.CustoMaoDeObraDTO;
import br.com.cernebr.gateway_nacional.licitacoes.custos.service.CustosMaoDeObraService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/licitacoes/custos-mao-de-obra")
public class CustosMaoDeObraController {

    private final CustosMaoDeObraService service;

    public CustosMaoDeObraController(CustosMaoDeObraService service) {
        this.service = service;
    }

    @GetMapping
    public CustoMaoDeObraDTO getCustoMaoDeObra(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo) {
        return service.obterCustoMaoDeObra(ibge, cbo);
    }
}
