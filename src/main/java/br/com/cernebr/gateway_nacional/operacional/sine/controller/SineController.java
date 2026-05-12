package br.com.cernebr.gateway_nacional.operacional.sine.controller;

import br.com.cernebr.gateway_nacional.operacional.sine.dto.VagaSineDTO;
import br.com.cernebr.gateway_nacional.operacional.sine.service.SineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operacional/sine")
public class SineController {

    private final SineService service;

    public SineController(SineService service) {
        this.service = service;
    }

    @GetMapping("/vagas")
    public VagaSineDTO getVagas(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo) {
        return service.obterVagas(ibge, cbo);
    }
}
