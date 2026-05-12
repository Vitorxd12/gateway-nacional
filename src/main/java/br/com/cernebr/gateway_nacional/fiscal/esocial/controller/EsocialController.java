package br.com.cernebr.gateway_nacional.fiscal.esocial.controller;

import br.com.cernebr.gateway_nacional.fiscal.esocial.dto.RiscoOcupacionalDTO;
import br.com.cernebr.gateway_nacional.fiscal.esocial.service.EsocialService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fiscal/esocial")
public class EsocialController {

    private final EsocialService service;

    public EsocialController(EsocialService service) {
        this.service = service;
    }

    @GetMapping("/risco-ocupacional")
    public RiscoOcupacionalDTO getRiscoOcupacional(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo) {
        return service.obterRiscoOcupacional(ibge, cbo);
    }
}
