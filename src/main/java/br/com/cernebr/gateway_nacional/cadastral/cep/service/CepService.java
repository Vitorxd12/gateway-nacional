package br.com.cernebr.gateway_nacional.cadastral.cep.service;

import br.com.cernebr.gateway_nacional.cadastral.cep.client.AwesomeApiClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.BrasilApiClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.ViaCepClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Resolve um CEP combinando dois padrões:
 * <ol>
 *   <li>{@link RefreshAheadCache} aplica soft-TTL/hard-TTL: o cache "ceps" tem
 *       hard-TTL de 30 dias (configurado em {@code CacheConfig}); o soft-TTL
 *       de 7 dias definido aqui dispara refresh assíncrono em background, sem
 *       bloquear a request — latência percebida pelo cliente fica constante
 *       mesmo na borda do TTL.</li>
 *   <li>{@link HedgedExecutor} dispara ViaCEP, BrasilAPI e AwesomeAPI em
 *       paralelo no caminho de loader; vence o primeiro com sucesso.</li>
 * </ol>
 *
 * <p>Por que 7 dias de soft-TTL: 23% do hard-TTL deixa folga generosa para
 * o background refresh terminar antes do hard-TTL expirar mesmo se o
 * provedor estiver lento; e ainda mantém os primeiros 23% do ciclo
 * "totalmente ociosos" — sem refresh redundante para chaves frias que só
 * foram acessadas uma vez.</p>
 *
 * <p>O enriquecimento IBGE ({@link IbgeEnrichmentService}) acontece <em>dentro</em>
 * do loader: o valor cacheado já é o enriquecido, então hits do soft-TTL e
 * background refreshes preservam o IBGE backfilled sem reprocessar.</p>
 *
 * <p>Métricas de provider são emitidas pelo {@link HedgedExecutor}; este
 * service não duplica instrumentação.</p>
 */
@Slf4j
@Service
public class CepService {

    private static final String DOMAIN = "cep";
    private static final String CACHE_NAME = "ceps";
    private static final Duration SOFT_TTL = Duration.ofDays(7);

    private final ViaCepClient viaCep;
    private final BrasilApiClient brasilApi;
    private final AwesomeApiClient awesomeApi;
    private final IbgeEnrichmentService ibgeEnrichmentService;
    private final GeoEnrichmentService geoEnrichmentService;
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public CepService(ViaCepClient viaCep,
                      BrasilApiClient brasilApi,
                      AwesomeApiClient awesomeApi,
                      IbgeEnrichmentService ibgeEnrichmentService,
                      GeoEnrichmentService geoEnrichmentService,
                      HedgedExecutor hedgedExecutor,
                      RefreshAheadCache refreshAheadCache) {
        this.viaCep = viaCep;
        this.brasilApi = brasilApi;
        this.awesomeApi = awesomeApi;
        this.ibgeEnrichmentService = ibgeEnrichmentService;
        this.geoEnrichmentService = geoEnrichmentService;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    public CepResponse findByCep(String cep) {
        return refreshAheadCache.get(CACHE_NAME, cep, SOFT_TTL, () -> loadFromProviders(cep));
    }

    /**
     * Pipeline do loader (executado uma vez por miss + uma vez por background
     * refresh do soft-TTL):
     * <ol>
     *   <li><b>Tier 1 — endereço base:</b> hedge entre ViaCEP, BrasilAPI v1 e
     *       AwesomeAPI. Vence o primeiro com sucesso.</li>
     *   <li><b>IBGE backfill:</b> preenche o código IBGE quando o provider
     *       vencedor não trouxe (ViaCEP/BrasilAPI v1 não têm).</li>
     *   <li><b>Geo backfill:</b> se o vencedor não trouxe lat/long
     *       (ViaCEP, BrasilAPI v1), cascata BrasilAPI v2 → Nominatim OSM até
     *       resolver. Falha total é silenciosa — devolve a resposta sem
     *       localização para preservar o contrato CEP base.</li>
     * </ol>
     *
     * <p>O resultado completo (endereço + IBGE + geo) entra no cache, então
     * hits subsequentes não pagam Nominatim — é o mesmo TTL de 30d hard /
     * 7d soft do cache {@code ceps}.</p>
     */
    private CepResponse loadFromProviders(String cep) {
        CepResponse raw = hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(viaCep.providerName(),     () -> viaCep.fetch(cep)),
                new NamedSupplier<>(brasilApi.providerName(),  () -> brasilApi.fetch(cep)),
                new NamedSupplier<>(awesomeApi.providerName(), () -> awesomeApi.fetch(cep))
        ));
        CepResponse comIbge = ibgeEnrichmentService.enrich(raw);
        return geoEnrichmentService.enrich(comIbge);
    }
}
