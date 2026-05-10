package br.com.cernebr.gateway_nacional.juridico.sancoes.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.juridico.sancoes.client.SancoesClient;
import br.com.cernebr.gateway_nacional.juridico.sancoes.dto.SancoesEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Façade sobre {@link SancoesClient}.
 *
 * <p>Cache de 7 dias (chave = CNPJ) é coerente com a publicação real:
 * sanções entram no CEIS com semanas de delay frente à publicação no DOU,
 * então atualizar mais frequentemente é trabalho perdido na maioria das
 * consultas. Empresas que precisam verificar imediatamente após uma
 * publicação podem invalidar via {@code DEL sancoes::{cnpj}} em Redis.</p>
 *
 * <h2>RAC com soft-TTL de 2 dias</h2>
 * <p>Single-provider — não há hedge a aplicar. Mas o {@link RefreshAheadCache}
 * encaixa: chaves quentes (CNPJs verificados recorrentemente em devida
 * diligência B2B) entram em refresh-ahead 2 dias após a última leitura,
 * mantendo a ordem de grandeza original do TTL e absorvendo a janela típica
 * de delay CEIS↔DOU sem expor o cliente à latência da CGU em caso de borda
 * do hard-TTL.</p>
 */
@Slf4j
@Service
public class SancoesService {

    private static final String DOMAIN = "sancoes";
    private static final String CACHE_NAME = "sancoes";
    private static final Duration SOFT_TTL = Duration.ofDays(2);

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final SancoesClient client;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache refreshAheadCache;

    public SancoesService(SancoesClient client,
                          MeterRegistry meterRegistry,
                          RefreshAheadCache refreshAheadCache) {
        this.client = client;
        this.meterRegistry = meterRegistry;
        this.refreshAheadCache = refreshAheadCache;
    }

    public SancoesEnvelope findByCnpj(String cnpj) {
        return refreshAheadCache.get(CACHE_NAME, cnpj, SOFT_TTL, () -> loadFromUpstream(cnpj));
    }

    private SancoesEnvelope loadFromUpstream(String cnpj) {
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
