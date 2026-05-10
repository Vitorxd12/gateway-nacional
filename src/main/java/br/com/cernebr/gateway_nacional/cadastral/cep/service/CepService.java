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
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public CepService(ViaCepClient viaCep,
                      BrasilApiClient brasilApi,
                      AwesomeApiClient awesomeApi,
                      IbgeEnrichmentService ibgeEnrichmentService,
                      HedgedExecutor hedgedExecutor,
                      RefreshAheadCache refreshAheadCache) {
        this.viaCep = viaCep;
        this.brasilApi = brasilApi;
        this.awesomeApi = awesomeApi;
        this.ibgeEnrichmentService = ibgeEnrichmentService;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    public CepResponse findByCep(String cep) {
        return refreshAheadCache.get(CACHE_NAME, cep, SOFT_TTL, () -> loadFromProviders(cep));
    }

    private CepResponse loadFromProviders(String cep) {
        CepResponse raw = hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(viaCep.providerName(),     () -> viaCep.fetch(cep)),
                new NamedSupplier<>(brasilApi.providerName(),  () -> brasilApi.fetch(cep)),
                new NamedSupplier<>(awesomeApi.providerName(), () -> awesomeApi.fetch(cep))
        ));
        return ibgeEnrichmentService.enrich(raw);
    }
}
