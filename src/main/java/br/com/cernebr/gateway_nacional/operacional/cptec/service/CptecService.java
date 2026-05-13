package br.com.cernebr.gateway_nacional.operacional.cptec.service;

import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.operacional.cptec.client.BrasilApiCptecClient;
import br.com.cernebr.gateway_nacional.operacional.cptec.client.CptecInpeClient;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CidadeCptecResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CondicaoAtualResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.OndasResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.PrevisaoClimaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orquestra clima/previsão/ondas para o setor logístico e agro com hedge
 * paralelo entre CPTEC/INPE direto e BrasilAPI.
 *
 * <h2>Por que hedge e não cascata</h2>
 * <p>CPTEC publica diretamente em XML legado (HTTP) e o servidor pica com
 * relativa frequência sob carga matinal (briefings agro). A BrasilAPI tem
 * latência mais alta porém estabilidade maior (Cloudflare na borda). Hedge
 * paralelo entrega o melhor dos dois mundos — o vencedor é cancelado em
 * cancellation do {@link HedgedExecutor}, preservando quota de ambos.</p>
 *
 * <h2>Cache</h2>
 * <p>Todas as rotas compartilham o cache {@code cptec} com TTL longo
 * (configurado em {@link br.com.cernebr.gateway_nacional.config.CacheConfig}).
 * Dados meteorológicos são previsão de até 6 dias — refrescar a cada hora
 * absorve 95% das consultas sem servir nada estranho ao usuário final.</p>
 */
@Slf4j
@Service
public class CptecService {

    private static final String DOMAIN = "cptec";
    private static final String CACHE = "cptec";

    private final CptecInpeClient inpe;
    private final BrasilApiCptecClient brasilApi;
    private final HedgedExecutor hedgedExecutor;

    public CptecService(CptecInpeClient inpe,
                        BrasilApiCptecClient brasilApi,
                        HedgedExecutor hedgedExecutor) {
        this.inpe = inpe;
        this.brasilApi = brasilApi;
        this.hedgedExecutor = hedgedExecutor;
    }

    @Cacheable(cacheNames = CACHE, key = "'cidade:' + #nome.toLowerCase()")
    public List<CidadeCptecResponse> searchCidades(String nome) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(inpe.providerName(), () -> inpe.searchCidades(nome)),
                new NamedSupplier<>(brasilApi.providerName(), () -> brasilApi.searchCidades(nome))
        ));
    }

    /**
     * Retorna o catálogo global de cidades mapeadas no CPTEC/INPE.
     *
     * <p>Cascata: <b>INPE direto → BrasilAPI</b>. O INPE devolve todas as cidades
     * quando a query é vazia; a BrasilAPI não expõe endpoint sem parâmetro,
     * portanto atua como placeholder (retorna vazio via default do provider).
     * Resultado cacheado em Redis por <b>24h</b> com chave única
     * {@code cptec::cidades-all} — o banco de cidades do INPE muda raras
     * vezes por década.</p>
     */
    @Cacheable(cacheNames = CACHE, key = "'cidades-all'")
    public List<CidadeCptecResponse> listAllCidades() {
        // Cascata sequencial: INPE é o único que tem o dump completo;
        // BrasilAPI age como safety net (retorna vazio mas não 503).
        try {
            List<CidadeCptecResponse> resultado = inpe.listAllCidades();
            if (!resultado.isEmpty()) {
                log.info("CPTEC listAllCidades: {} cidades via INPE direto.", resultado.size());
                return resultado;
            }
        } catch (Exception ex) {
            log.warn("CPTEC INPE falhou em listAllCidades ({}); tentando BrasilAPI.", ex.getMessage());
        }
        List<CidadeCptecResponse> fallback = brasilApi.listAllCidades();
        log.info("CPTEC listAllCidades: {} cidades via BrasilAPI.", fallback.size());
        return fallback;
    }

    @Cacheable(cacheNames = CACHE, key = "'capitais:atual'")
    public List<CondicaoAtualResponse> condicoesCapitais() {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(inpe.providerName(), inpe::condicoesCapitais),
                new NamedSupplier<>(brasilApi.providerName(), brasilApi::condicoesCapitais)
        ));
    }

    @Cacheable(cacheNames = CACHE, key = "'aeroporto:' + #icao.toUpperCase()")
    public CondicaoAtualResponse condicoesAeroporto(String icao) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(inpe.providerName(), () -> inpe.condicoesAeroporto(icao)),
                new NamedSupplier<>(brasilApi.providerName(), () -> brasilApi.condicoesAeroporto(icao))
        ));
    }

    @Cacheable(cacheNames = CACHE, key = "'previsao:' + #cityCode + ':' + #dias")
    public PrevisaoClimaResponse previsao(int cityCode, int dias) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(inpe.providerName(), () -> inpe.previsao(cityCode, dias)),
                new NamedSupplier<>(brasilApi.providerName(), () -> brasilApi.previsao(cityCode, dias))
        ));
    }

    /**
     * Previsão semanal a partir de coordenadas geográficas. Cache-key inclui
     * arredondamento a 4 casas decimais (~11 metros) para colapsar consultas
     * vindas de GPS com jitter natural — duas requisições do mesmo veículo em
     * pontos próximos compartilham o mesmo registro Redis.
     */
    @Cacheable(cacheNames = CACHE,
            key = "'previsao-semana:' + T(java.lang.Math).round(#lat * 10000) + ':' + T(java.lang.Math).round(#lon * 10000) + ':' + #dias")
    public PrevisaoClimaResponse previsaoSemana(double lat, double lon, int dias) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(inpe.providerName(), () -> inpe.previsaoSemana(lat, lon, dias)),
                new NamedSupplier<>(brasilApi.providerName(), () -> brasilApi.previsaoSemana(lat, lon, dias))
        ));
    }

    @Cacheable(cacheNames = CACHE, key = "'ondas:' + #cityCode + ':' + #dias")
    public OndasResponse ondas(int cityCode, int dias) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(inpe.providerName(), () -> inpe.ondas(cityCode, dias)),
                new NamedSupplier<>(brasilApi.providerName(), () -> brasilApi.ondas(cityCode, dias))
        ));
    }
}
