package br.com.cernebr.gateway_nacional.cadastral.cep.service;

import br.com.cernebr.gateway_nacional.cadastral.cep.client.BrasilApiV2GeoClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.GeoCepClientProvider;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.NominatimOsmClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse.Localizacao;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Backfill de coordenadas geográficas no payload do CEP.
 *
 * <h2>Estratégia: cascata oportunista</h2>
 * <p>Quando o tier 1 (hedge ViaCEP/BrasilAPI/AwesomeAPI) já trouxe lat/lng
 * (caso típico do AwesomeAPI), este service é <b>no-op</b> — não bate em
 * Nominatim, não polui CB, não consome quota. Caso contrário, dispara em
 * cascata BrasilAPI v2 → Nominatim OSM até obter coordenadas ou esgotar
 * tentativas.</p>
 *
 * <h2>Por que cascata e não hedge</h2>
 * <p>Hedge desperdiçaria a quota generosa do Nominatim no caminho feliz —
 * BrasilAPI v2 já cacheou internamente o resultado para a maioria dos CEPs
 * urbanos e responde em &lt;500 ms. Cascata reserva o Nominatim apenas para
 * o caso em que o caminho rápido falhou. Para o hot path (CEP urbano com
 * cache quente em algum dos providers), latência total adicionada ≈ uma
 * chamada single-provider.</p>
 *
 * <h2>Degradação graciosa</h2>
 * <p>Se ambos os providers falharem (Circuit Breakers abertos, timeout,
 * Nominatim recusando por rate-limit), o service <b>devolve o CepResponse
 * original sem localização</b> — não propaga 503 para o caller. Endereço é
 * o core do contrato CEP; geo é enriquecimento. Aviso fica em log
 * {@code WARN} para observabilidade.</p>
 *
 * <h2>Métricas</h2>
 * <p>Emite {@code gateway.provider.requests} e {@code gateway.provider.latency}
 * por provider de geo, mesma família dos demais clients — dashboards Grafana
 * filtram por {@code domain="cep-geo"}.</p>
 */
@Slf4j
@Service
public class GeoEnrichmentService {

    private static final String DOMAIN = "cep-geo";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<GeoCepClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public GeoEnrichmentService(BrasilApiV2GeoClient brasilApiV2,
                                NominatimOsmClient nominatim,
                                MeterRegistry meterRegistry) {
        // Ordem deliberada: BrasilAPI v2 primeiro (cache do lado deles, latência
        // estável); Nominatim segundo (rate-limited, deve ser usado com parcimônia).
        this.providersInOrder = List.of(brasilApiV2, nominatim);
        this.meterRegistry = meterRegistry;
    }

    public CepResponse enrich(CepResponse base) {
        if (base == null) return null;
        if (base.localizacao() != null) {
            // Caminho feliz — tier 1 já resolveu (AwesomeAPI). No-op silencioso.
            return base;
        }
        // Sem mínimo de campos para Nominatim filtrar com precisão — pular.
        if (base.uf() == null || base.localidade() == null) {
            return base;
        }

        for (GeoCepClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                Optional<Localizacao> hit = provider.geocodificar(base);
                if (hit.isPresent()) {
                    recordOutcome(provider.providerName(), "success", sample);
                    log.debug("Geo enrich {} resolvido por provider={} (precisao={})",
                            base.cep(), provider.providerName(), hit.get().precisao());
                    return base.withLocalizacao(hit.get());
                }
                recordOutcome(provider.providerName(), "not-found", sample);
                log.debug("Geo enrich {} sem match em provider={}", base.cep(), provider.providerName());
            } catch (ResourceUnavailableException ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Geo provider {} falhou para cep={} ({}). Cascata para o próximo.",
                        provider.providerName(), base.cep(), ex.getMessage());
            }
        }
        // Esgotamos a cascata sem geo. Resposta original segue sem localização —
        // contrato preserva backward-compat (campo nullable).
        log.warn("Geo enrichment exauriu providers para cep={}; respondendo sem localização.", base.cep());
        return base;
    }

    private void recordOutcome(String providerName, String outcome, Timer.Sample sample) {
        String tag = providerName.toLowerCase(Locale.ROOT);
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", tag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", tag,
                "outcome", outcome).increment();
    }
}
