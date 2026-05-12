package br.com.cernebr.gateway_nacional.saude.sigtap.controller;

import br.com.cernebr.gateway_nacional.saude.sigtap.dto.AuditoriaSigtapDTO;
import br.com.cernebr.gateway_nacional.saude.sigtap.service.SigtapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/saude/sigtap")
public class SigtapController {

    private final SigtapService service;

    public SigtapController(SigtapService service) {
        this.service = service;
    }

    @GetMapping("/auditoria")
    public AuditoriaSigtapDTO getAuditoria(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo,
            @RequestParam("procedimento") String procedimento) {
        return service.auditarProcedimento(ibge, cbo, procedimento);
    }
}
