package br.com.cernebr.gateway_nacional.cadastral.cnpj.service;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.BrasilApiClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.MinhaReceitaClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.client.ReceitaWsClient;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Resolve um CNPJ combinando dois padrões:
 * <ol>
 *   <li>{@link RefreshAheadCache} aplica soft-TTL/hard-TTL: o cache "cnpjs"
 *       tem hard-TTL de 24h (configurado em {@code CacheConfig}); o soft-TTL
 *       de 6h definido aqui dispara refresh assíncrono em background, sem
 *       bloquear a request — latência percebida pelo cliente fica constante
 *       mesmo na borda do TTL.</li>
 *   <li>{@link HedgedExecutor} dispara BrasilAPI, ReceitaWS e MinhaReceita
 *       em paralelo no caminho de loader; vence o primeiro com sucesso.</li>
 * </ol>
 *
 * <p>Por que 6h de soft-TTL no cenário B2B: dados cadastrais de PJ mudam com
 * baixa frequência, mas as 18h restantes do hard-TTL ainda asseguram que uma
 * mudança de razão social/situação cadastral propague no mesmo dia útil.
 * Os primeiros 6h ficam silenciosos — sem refresh para chave fria de uso
 * pontual; chaves quentes (clientes consultando o mesmo CNPJ recorrentemente
 * ao longo do dia) entram em refresh-ahead na primeira leitura após 6h.</p>
 *
 * <p>Métricas de provider são emitidas pelo {@link HedgedExecutor}; este
 * service não duplica instrumentação.</p>
 */
@Slf4j
@Service
public class CnpjService {

    private static final String DOMAIN = "cnpj";
    private static final String CACHE_NAME = "cnpjs";
    private static final Duration SOFT_TTL = Duration.ofHours(6);

    private final BrasilApiClient brasilApi;
    private final ReceitaWsClient receitaWs;
    private final MinhaReceitaClient minhaReceita;
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public CnpjService(BrasilApiClient brasilApi,
                       ReceitaWsClient receitaWs,
                       MinhaReceitaClient minhaReceita,
                       HedgedExecutor hedgedExecutor,
                       RefreshAheadCache refreshAheadCache) {
        this.brasilApi = brasilApi;
        this.receitaWs = receitaWs;
        this.minhaReceita = minhaReceita;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    public CnpjResponse findByCnpj(String cnpj) {
        return refreshAheadCache.get(CACHE_NAME, cnpj, SOFT_TTL, () -> loadFromProviders(cnpj));
    }

    private CnpjResponse loadFromProviders(String cnpj) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(brasilApi.providerName(),    () -> brasilApi.fetch(cnpj)),
                new NamedSupplier<>(receitaWs.providerName(),    () -> receitaWs.fetch(cnpj)),
                new NamedSupplier<>(minhaReceita.providerName(), () -> minhaReceita.fetch(cnpj))
        ));
    }
}
