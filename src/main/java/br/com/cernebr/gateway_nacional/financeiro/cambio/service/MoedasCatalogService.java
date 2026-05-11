package br.com.cernebr.gateway_nacional.financeiro.cambio.service;

import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.financeiro.cambio.client.BcbOlindaMoedasClient;
import br.com.cernebr.gateway_nacional.financeiro.cambio.client.BrasilApiMoedasClient;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.MoedaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orquestra a listagem pública do catálogo PTAX de moedas via hedge entre
 * BCB OLINDA (fonte canônica) e BrasilAPI (proxy). Vence o primeiro a
 * responder com sucesso; o {@link HedgedExecutor} cancela os perdedores
 * para preservar quota.
 *
 * <p>Reutiliza o cache {@code ptaxCatalog} (hard-TTL 30d configurado em
 * {@link br.com.cernebr.gateway_nacional.config.CacheConfig}) sob uma chave
 * separada {@code 'detail'} — o registro {@code 'all'} continua sendo o
 * {@code Set<String>} consumido pelo {@code CambioService} para validação
 * de pares. As duas entradas coexistem na mesma instância Redis.</p>
 */
@Slf4j
@Service
public class MoedasCatalogService {

    private static final String DOMAIN = "cambio-moedas";

    private final BcbOlindaMoedasClient bcb;
    private final BrasilApiMoedasClient brasilApi;
    private final HedgedExecutor hedgedExecutor;

    public MoedasCatalogService(BcbOlindaMoedasClient bcb,
                                BrasilApiMoedasClient brasilApi,
                                HedgedExecutor hedgedExecutor) {
        this.bcb = bcb;
        this.brasilApi = brasilApi;
        this.hedgedExecutor = hedgedExecutor;
    }

    @Cacheable(cacheNames = "ptaxCatalog", key = "'detail'")
    public List<MoedaResponse> listAll() {
        List<MoedaResponse> moedas = hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(bcb.providerName(), bcb::listAll),
                new NamedSupplier<>(brasilApi.providerName(), brasilApi::listAll)
        ));
        log.info("Catálogo PTAX exposto via hedge — {} moedas", moedas.size());
        return moedas;
    }
}
