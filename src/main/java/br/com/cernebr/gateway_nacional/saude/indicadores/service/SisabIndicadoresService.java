package br.com.cernebr.gateway_nacional.saude.indicadores.service;

import br.com.cernebr.gateway_nacional.saude.indicadores.client.SisabIndicadoresClient;
import br.com.cernebr.gateway_nacional.saude.indicadores.dto.IndicadorSinteticoResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Façade do "Termômetro de Desempenho da APS".
 *
 * <p>Cache de 30 dias é aceitável aqui (vs. 24h dos processos judiciais)
 * porque uma vez que o Ministério da Saúde consolida o quadrimestre, a
 * nota é frozen — só muda em portaria de reabertura administrativa, raro.
 * A chave concatena {@code ibge:quadrimestre} para isolar municípios e
 * permitir invalidação cirúrgica via {@code DEL indicadoresAps::355030:2025Q3}.</p>
 */
@Slf4j
@Service
public class SisabIndicadoresService {

    private static final String DOMAIN = "indicadoresAps";
    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final SisabIndicadoresClient client;
    private final MeterRegistry meterRegistry;

    public SisabIndicadoresService(SisabIndicadoresClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "indicadoresAps", key = "#codigoIbge + ':' + #quadrimestre")
    public IndicadorSinteticoResponse consultar(String codigoIbge, String quadrimestre) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            IndicadorSinteticoResponse response = client.fetch(codigoIbge, quadrimestre);
            recordOutcome(client.providerName(), "success", sample);
            log.info("Indicadores APS resolvidos ibge={} quadrimestre={} notaFinal={}",
                    codigoIbge, quadrimestre, response.notaFinal());
            return response;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("Provider {} falhou para indicadores ibge={} quadrimestre={} ({}).",
                    client.providerName(), codigoIbge, quadrimestre, ex.getMessage());
            throw ex;
        }
    }

    private void recordOutcome(String providerName, String outcome, Timer.Sample sample) {
        String providerTag = providerName.toLowerCase(Locale.ROOT);
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", providerTag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", providerTag,
                "outcome", outcome).increment();
    }
}
