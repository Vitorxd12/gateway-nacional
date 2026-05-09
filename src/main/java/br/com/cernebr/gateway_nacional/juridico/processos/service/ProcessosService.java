package br.com.cernebr.gateway_nacional.juridico.processos.service;

import br.com.cernebr.gateway_nacional.juridico.processos.client.ProcessosClient;
import br.com.cernebr.gateway_nacional.juridico.processos.dto.ProcessoResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Façade sobre {@link ProcessosClient}.
 *
 * <p>Cache de 24h é o teto razoável para movimentação processual: o DataJud
 * é abastecido em D+1 pelos tribunais (a maioria publica em batches noturnos),
 * de modo que invalidar antes não traz informação nova. Casos específicos
 * (advogado seguindo audiência) podem invalidar via {@code DEL processos::{num}}.</p>
 */
@Slf4j
@Service
public class ProcessosService {

    private static final String DOMAIN = "processos";
    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final ProcessosClient client;
    private final MeterRegistry meterRegistry;

    public ProcessosService(ProcessosClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "processos", key = "#numeroProcesso")
    public ProcessoResponse findByNumero(String numeroProcesso) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ProcessoResponse response = client.fetchByNumero(numeroProcesso);
            recordOutcome(client.providerName(), "success", sample);
            log.info("Processo resolvido upstream numero={} tribunal={}", numeroProcesso, response.tribunal());
            return response;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("Provider {} falhou para processo numero={} ({}).",
                    client.providerName(), numeroProcesso, ex.getMessage());
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
