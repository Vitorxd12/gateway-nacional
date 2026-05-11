package br.com.cernebr.gateway_nacional.cadastral.ddd.service;

import br.com.cernebr.gateway_nacional.cadastral.ddd.client.AnatelDddClient;
import br.com.cernebr.gateway_nacional.cadastral.ddd.client.BrasilApiDddClient;
import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddResponse;
import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddSnapshot;
import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddSnapshot.DddEntry;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolve consultas de DDD priorizando a fonte canônica ANATEL.
 *
 * <h2>Cascata (ordem mandatória)</h2>
 * <ol>
 *   <li><b>Tier 1 — ANATEL direto:</b> {@link AnatelDddClient} baixa o CSV
 *       oficial e mantém um {@link DddSnapshot} cacheado com RAC. Lookup
 *       por DDD opera em memória sobre o snapshot.</li>
 *   <li><b>Tier 2 — BrasilAPI fallback:</b> só acionado quando o snapshot
 *       interno <em>não consegue carregar</em> (ANATEL fora do ar + cache
 *       expirado). NÃO é acionado quando o snapshot carrega mas o DDD não
 *       existe (404 é resultado determinístico, propagado direto).</li>
 * </ol>
 *
 * <p><b>Diretriz mandatória:</b> a consulta direta à ANATEL nunca é
 * pulada em favor da BrasilAPI. A BrasilAPI só serve como rede de
 * proteção para indisponibilidade real da fonte oficial.</p>
 */
@Slf4j
@Service
public class DddService {

    private static final String CACHE_NAME = "ddd";
    private static final String CACHE_KEY = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(90);

    private final AnatelDddClient anatelClient;
    private final BrasilApiDddClient brasilApiFallbackClient;
    private final RefreshAheadCache refreshAheadCache;

    public DddService(AnatelDddClient anatelClient,
                      BrasilApiDddClient brasilApiFallbackClient,
                      RefreshAheadCache refreshAheadCache) {
        this.anatelClient = anatelClient;
        this.brasilApiFallbackClient = brasilApiFallbackClient;
        this.refreshAheadCache = refreshAheadCache;
    }

    public DddResponse findByDdd(String ddd) {
        DddSnapshot snapshot;
        try {
            snapshot = loadSnapshot();
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("ANATEL DDD snapshot indisponível ({}). Cascateando pra BrasilAPI fallback (ddd={}).",
                    tier1Failure.getMessage(), ddd);
            try {
                return brasilApiFallbackClient.findByDdd(ddd);
            } catch (ResourceNotFoundException notFound) {
                throw notFound;
            } catch (RuntimeException brasilApiFailure) {
                ResourceUnavailableException unified = new ResourceUnavailableException("ddd",
                        "ANATEL e BrasilAPI (fallback) falharam para DDD " + ddd, brasilApiFailure);
                unified.addSuppressed(tier1Failure);
                throw unified;
            }
        }

        // Snapshot ANATEL carregado — filtra em memória.
        String state = null;
        List<String> cities = new ArrayList<>();
        for (DddEntry entry : snapshot.entries()) {
            if (ddd.equals(entry.ddd())) {
                if (state == null) state = entry.state();
                cities.add(entry.city());
            }
        }

        if (cities.isEmpty()) {
            throw new ResourceNotFoundException("DDD",
                    "DDD " + ddd + " não encontrado no quadro nacional da ANATEL.");
        }
        return new DddResponse(state, cities);
    }

    private DddSnapshot loadSnapshot() {
        return refreshAheadCache.get(CACHE_NAME, CACHE_KEY, SOFT_TTL,
                anatelClient::fetchSnapshot);
    }
}
