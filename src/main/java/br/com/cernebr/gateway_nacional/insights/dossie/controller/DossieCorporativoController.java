package br.com.cernebr.gateway_nacional.insights.dossie.controller;

import br.com.cernebr.gateway_nacional.insights.dossie.dto.DossieCorporativoResponse;
import br.com.cernebr.gateway_nacional.insights.dossie.service.DossieCorporativoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/insights/empresa")
@CrossOrigin(origins = "*")
public class DossieCorporativoController {

    private final DossieCorporativoService service;

    public DossieCorporativoController(DossieCorporativoService service) {
        this.service = service;
    }

    @GetMapping("/{cnpj}")
    public ResponseEntity<DossieCorporativoResponse> getDossie(@PathVariable String cnpj) {
        return ResponseEntity.ok(service.gerarDossie(cnpj));
    }
}
