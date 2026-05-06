package br.com.cernebr.gateway_nacional.cnpj.service;

import br.com.cernebr.gateway_nacional.cnpj.client.BrasilApiClient;
import br.com.cernebr.gateway_nacional.cnpj.client.CnpjClientProvider;
import br.com.cernebr.gateway_nacional.cnpj.client.MinhaReceitaClient;
import br.com.cernebr.gateway_nacional.cnpj.client.ReceitaWsClient;
import br.com.cernebr.gateway_nacional.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the cascade fallback across multiple CNPJ providers.
 * Order: BrasilAPI → ReceitaWS → MinhaReceita. Successful resolutions are
 * cached by CNPJ key to minimize upstream load (CNPJ data changes rarely).
 */
@Slf4j
@Service
public class CnpjService {

    private static final String AGGREGATE_PROVIDER = "all-providers";

    private final List<CnpjClientProvider> providersInOrder;

    public CnpjService(BrasilApiClient primary,
                       ReceitaWsClient secondary,
                       MinhaReceitaClient tertiary) {
        this.providersInOrder = List.of(primary, secondary, tertiary);
    }

    @Cacheable(cacheNames = "cnpjs", key = "#cnpj")
    public CnpjResponse findByCnpj(String cnpj) {
        for (CnpjClientProvider provider : providersInOrder) {
            try {
                CnpjResponse response = provider.fetch(cnpj);
                log.info("CNPJ {} resolved by provider={}", cnpj, provider.providerName());
                return response;
            } catch (Exception ex) {
                log.warn("Provider {} failed for cnpj={} ({}). Cascading to next provider.",
                        provider.providerName(), cnpj, ex.getMessage());
            }
        }
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Todos os provedores de CNPJ falharam após o fallback em cascata.");
    }
}
