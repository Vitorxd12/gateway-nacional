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
 * Orchestrates the cascade fallback across multiple CEP providers.
 * Order: ViaCEP → BrasilAPI → AwesomeAPI. Successful resolutions are cached
 * by CEP key, so subsequent lookups never hit upstream providers again.
 */
@Slf4j
@Service
public class CepService {

    private static final String AGGREGATE_PROVIDER = "all-providers";

    private final List<CepClientProvider> providersInOrder;

    public CepService(ViaCepClient primary,
                      BrasilApiClient secondary,
                      AwesomeApiClient tertiary) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
    }

    @Cacheable(cacheNames = "ceps", key = "#cep")
    public CepResponse findByCep(String cep) {
        for (CepClientProvider provider : providersInOrder) {
            try {
                CepResponse response = provider.fetch(cep);
                log.info("CEP {} resolved by provider={}", cep, provider.providerName());
                return response;
            } catch (Exception ex) {
                log.warn("Provider {} failed for cep={} ({}). Cascading to next provider.",
                        provider.providerName(), cep, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de CEP falharam após o fallback em cascata.");
    }
}
