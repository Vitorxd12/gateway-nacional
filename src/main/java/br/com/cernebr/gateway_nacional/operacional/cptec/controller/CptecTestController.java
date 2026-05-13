package br.com.cernebr.gateway_nacional.operacional.cptec.controller;

import br.com.cernebr.gateway_nacional.operacional.cptec.client.CptecInpeClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CptecTestController {
    private final CptecInpeClient client;
    public CptecTestController(CptecInpeClient client) { this.client = client; }
    @GetMapping("/api/test-inpe")
    public Object test() {
        return client.listAllCidades();
    }
}
