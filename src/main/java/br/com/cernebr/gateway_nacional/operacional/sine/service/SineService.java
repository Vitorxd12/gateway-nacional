package br.com.cernebr.gateway_nacional.operacional.sine.service;

import br.com.cernebr.gateway_nacional.operacional.sine.dto.VagaSineDTO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

@Service
public class SineService {

    public VagaSineDTO obterVagas(String ibge, String cbo) {
        Random random = new Random(ibge.hashCode() + cbo.hashCode());
        int vagas = random.nextInt(15);
        String updated = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return new VagaSineDTO(ibge, cbo, vagas, "SINE/MTE", updated);
    }
}
