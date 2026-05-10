package br.com.cernebr.gateway_nacional.financeiro.pix.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.pix.client.BcbCsvPixClient;
import br.com.cernebr.gateway_nacional.financeiro.pix.client.BrasilApiPixClient;
import br.com.cernebr.gateway_nacional.financeiro.pix.client.PixParticipantesClientProvider;
import br.com.cernebr.gateway_nacional.financeiro.pix.dto.PixParticipantesResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Resolve a listagem completa de participantes do PIX combinando dois padrões
 * já estabelecidos no projeto:
 * <ol>
 *   <li>{@link RefreshAheadCache} aplica soft-TTL/hard-TTL sobre o cache
 *       {@code pixParticipantes} (hard 24h, soft 6h): a lista é regenerada
 *       diariamente pelo BCB; 6h de janela soft entrega refresh oportunista
 *       sem disparar o trabalho de download/parse para chaves frias.</li>
 *   <li><b>Cascata sequencial</b> (não hedge) entre BrasilAPI primário e
 *       BCB CSV fallback. Hedge seria desperdício aqui — o BCB CSV é caro
 *       (download de arquivo + parsing) e disparar em paralelo invocaria-o
 *       sempre, mesmo quando a BrasilAPI já respondeu.</li>
 * </ol>
 *
 * <h2>Por que cascata e não hedge (RULE B)</h2>
 * <p>RULE B literal do brief: "fallback só acionado se o primário falhar".
 * Padrão alinhado com {@link br.com.cernebr.gateway_nacional.cadastral.cnae.service.CnaeService}
 * e {@link br.com.cernebr.gateway_nacional.financeiro.bancos.service.BancoService},
 * que também têm fallback caro (snapshot local) e mantêm cascata por essa
 * mesma razão.</p>
 *
 * <h2>Métricas</h2>
 * <p>Emite {@code gateway.provider.requests} e {@code gateway.provider.latency}
 * por tentativa, mantendo o shape consistente com os demais services em
 * cascata. Métricas só disparam em cache miss — RAC curto-circuita o loader
 * em hit.</p>
 */
@Slf4j
@Service
public class PixService {

    private static final String DOMAIN = "pix";
    private static final String AGGREGATE_PROVIDER = "all-providers";
    private static final String CACHE_NAME = "pixParticipantes";
    private static final String CACHE_KEY = "all";
    private static final Duration SOFT_TTL = Duration.ofHours(6);

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<PixParticipantesClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;
    private final RefreshAheadCache refreshAheadCache;

    public PixService(BrasilApiPixClient primary,
                      BcbCsvPixClient secondary,
                      MeterRegistry meterRegistry,
                      RefreshAheadCache refreshAheadCache) {
        this.providersInOrder = List.of(primary, secondary);
        this.meterRegistry = meterRegistry;
        this.refreshAheadCache = refreshAheadCache;
    }

    public PixParticipantesResponse listParticipantes() {
        return refreshAheadCache.get(CACHE_NAME, CACHE_KEY, SOFT_TTL,
                this::loadFromProvidersInCascade);
    }

    private PixParticipantesResponse loadFromProvidersInCascade() {
        Throwable lastFailure = null;

        for (PixParticipantesClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                PixParticipantesResponse response = provider.fetchAll();
                recordOutcome(provider.providerName(), "success", sample);
                log.info("PIX participantes resolved by provider={} count={} dataReferencia={}",
                        provider.providerName(), response.total(), response.dataReferencia());
                return response;
            } catch (Exception ex) {
                lastFailure = ex;
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for PIX participantes ({}). Cascading to next provider.",
                        provider.providerName(), ex.getMessage());
            }
        }

        // Exaustão total — GlobalExceptionHandler converte em 503 ProblemDetail
        // com {provider: "pix"} no payload (RFC 7807).
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de PIX participantes falharam após o fallback em cascata.",
                lastFailure);
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
