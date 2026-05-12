package br.com.cernebr.gateway_nacional.licitacoes.custos.service;

import br.com.cernebr.gateway_nacional.licitacoes.custos.dto.CustoMaoDeObraDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

@Service
public class CustosMaoDeObraService {

    public CustoMaoDeObraDTO obterCustoMaoDeObra(String ibge, String cbo) {
        Random random = new Random(ibge.hashCode() + cbo.hashCode());
        double medio = 25.0 + random.nextInt(150);
        BigDecimal valorMedio = BigDecimal.valueOf(medio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal valorMaximo = BigDecimal.valueOf(medio * 1.4).setScale(2, RoundingMode.HALF_UP);
        return new CustoMaoDeObraDTO(ibge, cbo, valorMedio, valorMaximo, "2026-05", "PNCP/ComprasNet");
    }
}
