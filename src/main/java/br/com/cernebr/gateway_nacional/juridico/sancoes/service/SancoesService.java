package br.com.cernebr.gateway_nacional.juridico.sancoes.service;

import br.com.cernebr.gateway_nacional.juridico.sancoes.client.SancoesClient;
import br.com.cernebr.gateway_nacional.juridico.sancoes.dto.SancoesEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Façade sobre {@link SancoesClient}.
 *
 * <p>Cache de 7 dias (chave = CNPJ) é coerente com a publicação real:
 * sanções entram no CEIS com semanas de delay frente à publicação no DOU,
 * então atualizar mais frequentemente é trabalho perdido na maioria das
 * consultas. Empresas que precisam verificar imediatamente após uma
 * publicação podem invalidar via {@code DEL sancoes::{cnpj}} em Redis.</p>
 */
@Slf4j
@Service
public class SancoesService {

    private static final String DOMAIN = "sancoes";
    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final SancoesClient client;
    private final MeterRegistry meterRegistry;

    public SancoesService(SancoesClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "sancoes", key = "#cnpj")
    public SancoesEnvelope findByCnpj(String cnpj) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SancoesEnvelope envelope = new SancoesEnvelope(client.fetchByCnpj(cnpj));
            recordOutcome(client.providerName(), "success", sample);
            log.info("Sanções resolvidas upstream cnpj={} count={}", cnpj, envelope.sancoes().size());
            return envelope;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("Provider {} falhou para sanções cnpj={} ({}).",
                    client.providerName(), cnpj, ex.getMessage());
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
