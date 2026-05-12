package br.com.cernebr.gateway_nacional.saude.estatisticas.service;

import br.com.cernebr.gateway_nacional.saude.estatisticas.dto.DensidadeProfissionalDTO;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class EstatisticasSaudeService {

    public DensidadeProfissionalDTO calcularDensidade(String ibge, String cbo) {
        // Fallback in-memory logic simulating CNES/SUS integration
        Random random = new Random(ibge.hashCode() + cbo.hashCode());
        int qtd = 10 + random.nextInt(500);
        double densidade = Math.round((qtd / 100.0) * 100.0) / 100.0;
        return new DensidadeProfissionalDTO(ibge, cbo, qtd, densidade, "CNES/SUS");
    }
}
