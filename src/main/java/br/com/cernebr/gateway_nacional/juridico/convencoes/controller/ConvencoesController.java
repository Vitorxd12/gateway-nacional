package br.com.cernebr.gateway_nacional.juridico.convencoes.controller;

import br.com.cernebr.gateway_nacional.juridico.convencoes.dto.PisoSalarialDTO;
import br.com.cernebr.gateway_nacional.juridico.convencoes.service.ConvencoesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/juridico/convencoes")
public class ConvencoesController {

    private final ConvencoesService service;

    public ConvencoesController(ConvencoesService service) {
        this.service = service;
    }

    @GetMapping("/piso")
    public PisoSalarialDTO getPisoSalarial(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo) {
        return service.obterPisoSalarial(ibge, cbo);
    }
}
