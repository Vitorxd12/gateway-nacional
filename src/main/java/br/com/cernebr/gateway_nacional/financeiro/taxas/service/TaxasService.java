package br.com.cernebr.gateway_nacional.financeiro.taxas.service;

import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.financeiro.taxas.client.BcbSgsTaxasClient;
import br.com.cernebr.gateway_nacional.financeiro.taxas.client.BrasilApiTaxasClient;
import br.com.cernebr.gateway_nacional.financeiro.taxas.client.HgBrasilTaxasClient;
import br.com.cernebr.gateway_nacional.financeiro.taxas.dto.TaxaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolve a sigla de uma taxa (SELIC, IPCA, CDI…) disparando BrasilAPI, BCB SGS
 * e HG Brasil em paralelo via {@link HedgedExecutor}; vence o primeiro a
 * responder com sucesso.
 *
 * <p>A key do cache normaliza a sigla para uppercase, então {@code "cdi"},
 * {@code "Cdi"} e {@code "CDI"} compartilham a mesma entrada Redis — comportamento
 * preservado da implementação anterior em cascata.</p>
 *
 * <p>Métricas são emitidas pelo {@link HedgedExecutor}.</p>
 */
@Slf4j
@Service
public class TaxasService {

    private static final String DOMAIN = "taxas";

    private final BrasilApiTaxasClient brasilApi;
    private final BcbSgsTaxasClient bcbSgs;
    private final HgBrasilTaxasClient hgBrasil;
    private final HedgedExecutor hedgedExecutor;

    public TaxasService(BrasilApiTaxasClient brasilApi,
                        BcbSgsTaxasClient bcbSgs,
                        HgBrasilTaxasClient hgBrasil,
                        HedgedExecutor hedgedExecutor) {
        this.brasilApi = brasilApi;
        this.bcbSgs = bcbSgs;
        this.hgBrasil = hgBrasil;
        this.hedgedExecutor = hedgedExecutor;
    }

    @Cacheable(cacheNames = "taxas", key = "#sigla.toUpperCase()")
    public TaxaResponse findBySigla(String sigla) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(brasilApi.providerName(), () -> brasilApi.fetch(sigla)),
                new NamedSupplier<>(bcbSgs.providerName(),    () -> bcbSgs.fetch(sigla)),
                new NamedSupplier<>(hgBrasil.providerName(),  () -> hgBrasil.fetch(sigla))
        ));
    }
}
