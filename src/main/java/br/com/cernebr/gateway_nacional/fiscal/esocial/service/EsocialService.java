package br.com.cernebr.gateway_nacional.fiscal.esocial.service;

import br.com.cernebr.gateway_nacional.fiscal.esocial.dto.RiscoOcupacionalDTO;
import org.springframework.stereotype.Service;

@Service
public class EsocialService {

    public RiscoOcupacionalDTO obterRiscoOcupacional(String ibge, String cbo) {
        String grauRisco = cbo.startsWith("225") ? "Grau 3" : "Grau 1";
        String fap = "1.0000";
        String periculosidade = cbo.startsWith("225") ? "Insalubridade 20%" : "Não aplicável";
        return new RiscoOcupacionalDTO(ibge, cbo, grauRisco, fap, periculosidade, "Tabela 27 eSocial");
    }
}
