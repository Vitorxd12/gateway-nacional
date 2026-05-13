package br.com.cernebr.gateway_nacional.insights.raiox.controller;

import br.com.cernebr.gateway_nacional.insights.raiox.dto.RaioXMunicipalResponse;
import br.com.cernebr.gateway_nacional.insights.raiox.service.RaioXMunicipalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/insights/municipio")
@CrossOrigin(origins = "*")
public class RaioXMunicipalController {

    private final RaioXMunicipalService service;

    public RaioXMunicipalController(RaioXMunicipalService service) {
        this.service = service;
    }

    @GetMapping("/{ibge}")
    public ResponseEntity<RaioXMunicipalResponse> getRaioX(@PathVariable String ibge) {
        return ResponseEntity.ok(service.gerarRaioX(ibge));
    }
}
