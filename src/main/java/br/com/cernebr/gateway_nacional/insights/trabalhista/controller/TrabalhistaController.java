package br.com.cernebr.gateway_nacional.insights.trabalhista.controller;

import br.com.cernebr.gateway_nacional.insights.trabalhista.dto.RemuneracaoMediaDTO;
import br.com.cernebr.gateway_nacional.insights.trabalhista.service.TrabalhistaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights/trabalhista")
public class TrabalhistaController {

    private final TrabalhistaService service;

    public TrabalhistaController(TrabalhistaService service) {
        this.service = service;
    }

    @GetMapping("/remuneracao")
    public RemuneracaoMediaDTO getRemuneracao(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo) {
        return service.obterRemuneracaoMedia(ibge, cbo);
    }
}
