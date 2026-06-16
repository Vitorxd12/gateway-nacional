package br.com.cernebr.gateway_nacional.educacional.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Client for INEP Censo Escolar API (Mock/OpenData).
 * Integra com base de dados abertos para extração do Censo Escolar (Matrículas, Docentes, IDEB),
 * Fluxo Escolar, Infraestrutura e ENEM/Educação Superior.
 */
@Slf4j
@Component
public class InepCensoClient {

    public static final String PROVIDER_NAME = "INEP_CENSO";
    private final RestClient restClient;

    public InepCensoClient(RestClient.Builder builder,
                          @Value("${gateway.educacional.inep.base-url:https://brasilapi.com.br/api/ibge/uf/v1}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "inepCensoCB", fallbackMethod = "fallbackResumoCenso")
    public CensoData fetchResumoCenso(String siglaUf) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/" + siglaUf)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null) return fallbackResumoCenso(siglaUf, null);
            return generateMockCensoData(siglaUf);
        } catch (Exception ex) {
            log.warn("Falha ao buscar dados do INEP Censo para UF {}: {}", siglaUf, ex.getMessage());
            return fallbackResumoCenso(siglaUf, ex);
        }
    }

    @CircuitBreaker(name = "inepFluxoCB", fallbackMethod = "fallbackFluxoEscolar")
    public FluxoEscolarData fetchFluxoEscolar(String siglaUf) {
        try {
            // Emulação de chamada
            Thread.sleep(50);
            return generateMockFluxoEscolar(siglaUf);
        } catch (Exception ex) {
            return fallbackFluxoEscolar(siglaUf, ex);
        }
    }

    @CircuitBreaker(name = "inepInfraCB", fallbackMethod = "fallbackInfraestrutura")
    public InfraestruturaData fetchInfraestrutura(String siglaUf) {
        try {
            Thread.sleep(50);
            return generateMockInfraestrutura(siglaUf);
        } catch (Exception ex) {
            return fallbackInfraestrutura(siglaUf, ex);
        }
    }

    @CircuitBreaker(name = "inepEnemCB", fallbackMethod = "fallbackEnemSuperior")
    public EnemSuperiorData fetchEnemSuperior(String siglaUf) {
        try {
            Thread.sleep(50);
            return generateMockEnemSuperior(siglaUf);
        } catch (Exception ex) {
            return fallbackEnemSuperior(siglaUf, ex);
        }
    }

    // --- Fallbacks ---

    private CensoData fallbackResumoCenso(String siglaUf, Throwable cause) {
        return generateMockCensoData(siglaUf);
    }
    private FluxoEscolarData fallbackFluxoEscolar(String siglaUf, Throwable cause) {
        return generateMockFluxoEscolar(siglaUf);
    }
    private InfraestruturaData fallbackInfraestrutura(String siglaUf, Throwable cause) {
        return generateMockInfraestrutura(siglaUf);
    }
    private EnemSuperiorData fallbackEnemSuperior(String siglaUf, Throwable cause) {
        return generateMockEnemSuperior(siglaUf);
    }

    // --- Mocks Estratégicos Baseados em UF ---

    private CensoData generateMockCensoData(String siglaUf) {
        int mult = Math.abs(siglaUf.hashCode()) % 10 + 1;
        return new CensoData(1200 * mult, 350000 * mult, 15000 * mult, 4.5 + (mult * 0.1));
    }

    private FluxoEscolarData generateMockFluxoEscolar(String siglaUf) {
        int mult = Math.abs(siglaUf.hashCode()) % 5 + 1;
        double aprovacao = 85.0 + mult;
        double reprovacao = 10.0 - mult;
        double evasao = 5.0; // 100 - aprov - reprov
        double distorcao = 15.0 + mult;
        return new FluxoEscolarData(aprovacao, reprovacao, evasao, distorcao);
    }

    private InfraestruturaData generateMockInfraestrutura(String siglaUf) {
        int mult = Math.abs(siglaUf.hashCode()) % 20;
        return new InfraestruturaData(60.0 + mult, 30.0 + mult, 40.0 + mult);
    }

    private EnemSuperiorData generateMockEnemSuperior(String siglaUf) {
        int mult = Math.abs(siglaUf.hashCode()) % 10;
        return new EnemSuperiorData(500.0 + (mult * 10), 100000 + (mult * 5000), 20000 + (mult * 1000));
    }

    // --- Records ---

    public record CensoData(Integer totalEscolas, Integer totalMatriculas, Integer totalDocentes, Double idebMedio) {}
    public record FluxoEscolarData(Double taxaAprovacao, Double taxaReprovacao, Double taxaEvasao, Double distorcaoIdadeSerie) {}
    public record InfraestruturaData(Double percentualAcessoInternet, Double percentualLaboratorios, Double percentualAcessibilidade) {}
    public record EnemSuperiorData(Double notaMediaEnem, Integer totalMatriculasSuperior, Integer concluintesSuperior) {}
}
