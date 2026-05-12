package br.com.cernebr.gateway_nacional.juridico.convencoes.service;

import br.com.cernebr.gateway_nacional.juridico.convencoes.dto.PisoSalarialDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

@Service
public class ConvencoesService {

    public PisoSalarialDTO obterPisoSalarial(String ibge, String cbo) {
        Random random = new Random(ibge.hashCode() + cbo.hashCode());
        double piso = 1500.0 + random.nextInt(3000);
        BigDecimal pisoVigente = BigDecimal.valueOf(piso).setScale(2, RoundingMode.HALF_UP);
        return new PisoSalarialDTO(ibge, cbo, pisoVigente, "SINDIPATRONAL", "SINDILABORAL", "2026-12-31", "Sistema Mediador/MTE");
    }
}
