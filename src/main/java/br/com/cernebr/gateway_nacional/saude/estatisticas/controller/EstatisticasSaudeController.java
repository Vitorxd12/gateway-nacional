package br.com.cernebr.gateway_nacional.saude.estatisticas.controller;

import br.com.cernebr.gateway_nacional.saude.estatisticas.dto.DensidadeProfissionalDTO;
import br.com.cernebr.gateway_nacional.saude.estatisticas.service.EstatisticasSaudeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/saude/estatisticas")
public class EstatisticasSaudeController {

    private final EstatisticasSaudeService service;

    public EstatisticasSaudeController(EstatisticasSaudeService service) {
        this.service = service;
    }

    @GetMapping("/profissionais")
    public DensidadeProfissionalDTO getDensidadeProfissional(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo) {
        return service.calcularDensidade(ibge, cbo);
    }
}
