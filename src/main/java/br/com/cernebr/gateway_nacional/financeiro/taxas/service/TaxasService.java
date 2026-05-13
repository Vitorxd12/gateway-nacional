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
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Retorna todas as taxas principais (CDI, Selic, IPCA) de uma vez, em paralelo.
     *
     * <p>Cada sigla é buscada independentemente na cascata de providers e o resultado
     * agregado em um array canônico. Cacheado em Redis sob a chave {@code 'ALL'}
     * com TTL de 12 h — mesma janela dos lookups individuais.</p>
     *
     * <p>Falhas individuais são registradas em log e omitidas do array final:
     * o chamador recebe as taxas disponíveis, nunca um 503 por falha parcial.
     * Apenas se <em>todas</em> as 3 siglas falharem a lista virá vazia.</p>
     */
    @Cacheable(cacheNames = "taxas", key = "'ALL'")
    public List<TaxaResponse> listAll() {
        List<String> siglas = List.of("cdi", "selic", "ipca");
        List<TaxaResponse> resultado = new ArrayList<>();
        for (String sigla : siglas) {
            try {
                resultado.add(findBySigla(sigla));
            } catch (Exception ex) {
                log.warn("TaxasService.listAll: falha ao resolver sigla={} ({}); omitindo do bulk.",
                        sigla, ex.getMessage());
            }
        }
        return resultado;
    }
}
