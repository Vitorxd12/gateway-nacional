package br.com.cernebr.gateway_nacional.educacional.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Client for FNDE API (Fundo Nacional de Desenvolvimento da Educação).
 * Busca repasses do PNAE (Merenda), PNATE (Transporte) e PDDE (Manutenção).
 */
@Slf4j
@Component
public class FndeClient {

    public static final String PROVIDER_NAME = "FNDE";

    @CircuitBreaker(name = "fndeCB", fallbackMethod = "fallbackRepasses")
    public FndeRepassesData fetchRepasses(String siglaUf) {
        try {
            // Integração futura via RestClient com o Portal da Transparência FNDE
            // Simulando latência da rede
            Thread.sleep(50);
            return generateMockRepasses(siglaUf);
        } catch (Exception ex) {
            log.warn("Falha ao buscar repasses do FNDE para {}: {}", siglaUf, ex.getMessage());
            return fallbackRepasses(siglaUf, ex);
        }
    }

    private FndeRepassesData fallbackRepasses(String siglaUf, Throwable cause) {
        return generateMockRepasses(siglaUf);
    }

    private FndeRepassesData generateMockRepasses(String siglaUf) {
        int mult = Math.abs(siglaUf.hashCode()) % 10 + 1;
        // Valores em Reais (simulados)
        return new FndeRepassesData(
                1500000.0 * mult, // PNAE
                800000.0 * mult,  // PNATE
                500000.0 * mult   // PDDE
        );
    }

    public record FndeRepassesData(Double valorPnae, Double valorPnate, Double valorPdde) {}
}
