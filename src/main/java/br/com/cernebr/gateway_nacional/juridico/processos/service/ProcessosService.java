package br.com.cernebr.gateway_nacional.juridico.processos.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.juridico.processos.client.ProcessosClient;
import br.com.cernebr.gateway_nacional.juridico.processos.dto.ProcessoResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Façade sobre {@link ProcessosClient}.
 *
 * <p>Cache de 24h é o teto razoável para movimentação processual: o DataJud
 * é abastecido em D+1 pelos tribunais (a maioria publica em batches noturnos),
 * de modo que invalidar antes não traz informação nova. Casos específicos
 * (advogado seguindo audiência) podem invalidar via {@code DEL processos::{num}}.</p>
 *
 * <h2>RAC com soft-TTL de 6h</h2>
 * <p>Single-provider — não há hedge a aplicar. Mas o {@link RefreshAheadCache}
 * encaixa no caso de uso típico (advogado abre o mesmo processo várias vezes
 * ao longo do dia): a primeira leitura após 6h dispara refresh assíncrono,
 * o cliente recebe o valor velho de imediato e a próxima leitura já vê o
 * batch noturno do tribunal aplicado, sem expor a latência do DataJud.</p>
 */
@Slf4j
@Service
public class ProcessosService {

    private static final String DOMAIN = "processos";
    private static final String CACHE_NAME = "processos";
    private static final Duration SOFT_TTL = Duration.ofHours(6);

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final ProcessosClient client;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache refreshAheadCache;

    public ProcessosService(ProcessosClient client,
                            MeterRegistry meterRegistry,
                            RefreshAheadCache refreshAheadCache) {
        this.client = client;
        this.meterRegistry = meterRegistry;
        this.refreshAheadCache = refreshAheadCache;
    }

    public ProcessoResponse findByNumero(String numeroProcesso) {
        return refreshAheadCache.get(CACHE_NAME, numeroProcesso, SOFT_TTL,
                () -> loadFromUpstream(numeroProcesso));
    }

    private ProcessoResponse loadFromUpstream(String numeroProcesso) {
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
