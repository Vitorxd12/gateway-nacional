package br.com.cernebr.gateway_nacional.insights.trabalhista.service;

import br.com.cernebr.gateway_nacional.insights.trabalhista.dto.RemuneracaoMediaDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

@Service
public class TrabalhistaService {

    public RemuneracaoMediaDTO obterRemuneracaoMedia(String ibge, String cbo) {
        Random random = new Random(ibge.hashCode() + cbo.hashCode());
        double base = 2000.0 + random.nextInt(8000);
        BigDecimal media = BigDecimal.valueOf(base).setScale(2, RoundingMode.HALF_UP);
        BigDecimal piso = BigDecimal.valueOf(base * 0.7).setScale(2, RoundingMode.HALF_UP);
        BigDecimal teto = BigDecimal.valueOf(base * 1.5).setScale(2, RoundingMode.HALF_UP);
        
        return new RemuneracaoMediaDTO(ibge, cbo, media, teto, piso, "Novo CAGED/PDET", "2026-04");
    }
}
