package br.com.cernebr.gateway_nacional.saude.service;

import br.com.cernebr.gateway_nacional.saude.client.CnesWebClient;
import br.com.cernebr.gateway_nacional.saude.dto.ProfissionalCnesResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates CNES profissionais retrieval. Cache TTL of 15 days mirrors
 * the federal payment cycle — cadastro changes are usually consolidated at
 * competency closure, so a 15-day window absorbs the typical update tempo.
 *
 * <p>Cache key composes both ibge and CNES because the upstream is keyed by
 * {@code {ibge}{cnes}} — caching by CNES alone would risk serving the wrong
 * municipality's profissionais if two cities use coincident CNES codes
 * (rare but documented).</p>
 */
@Slf4j
@Service
public class CnesService {

    private static final String DOMAIN = "saude";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final CnesWebClient client;
    private final MeterRegistry meterRegistry;

    public CnesService(CnesWebClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "saude", key = "'cnes-' + #cnesBase + '-' + #ibge")
    public List<ProfissionalCnesResponse> findProfissionais(String cnesBase, String ibge) {
        String ibge6 = canonicalIbge(ibge);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<ProfissionalCnesResponse> profissionais = client.fetchProfissionais(cnesBase, ibge6);
            recordOutcome(client.providerName(), "success", sample);
            return profissionais;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("CNES provider failed for IBGE={} CNES={}: {}", ibge6, cnesBase, ex.getMessage());
            throw ex;
        }
    }

    private static String canonicalIbge(String ibge) {
        if (ibge == null) {
            throw new IllegalArgumentException("IBGE obrigatório.");
        }
        return ibge.length() >= 7 ? ibge.substring(0, 6) : ibge;
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
