package br.com.cernebr.gateway_nacional.cep.service;

import br.com.cernebr.gateway_nacional.cep.client.AwesomeApiClient;
import br.com.cernebr.gateway_nacional.cep.client.BrasilApiClient;
import br.com.cernebr.gateway_nacional.cep.client.CepClientProvider;
import br.com.cernebr.gateway_nacional.cep.client.ViaCepClient;
import br.com.cernebr.gateway_nacional.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the cascade fallback across multiple CEP providers and applies
 * in-memory IBGE enrichment before caching/returning the result.
 * Order: ViaCEP → BrasilAPI → AwesomeAPI.
 */
@Slf4j
@Service
public class CepService {

    private static final String AGGREGATE_PROVIDER = "all-providers";

    private final List<CepClientProvider> providersInOrder;
    private final IbgeEnrichmentService ibgeEnrichmentService;

    public CepService(ViaCepClient primary,
                      BrasilApiClient secondary,
                      AwesomeApiClient tertiary,
                      IbgeEnrichmentService ibgeEnrichmentService) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
        this.ibgeEnrichmentService = ibgeEnrichmentService;
    }

    @Cacheable(cacheNames = "ceps", key = "#cep")
    public CepResponse findByCep(String cep) {
        for (CepClientProvider provider : providersInOrder) {
            try {
                CepResponse raw = provider.fetch(cep);
                CepResponse enriched = ibgeEnrichmentService.enrich(raw);
                log.info("CEP {} resolved by provider={}", cep, provider.providerName());
                return enriched;
            } catch (Exception ex) {
                log.warn("Provider {} failed for cep={} ({}). Cascading to next provider.",
                        provider.providerName(), cep, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de CEP falharam após o fallback em cascata.");
    }
}
