package br.com.cernebr.gateway_nacional.cadastral.ddd.service;

import br.com.cernebr.gateway_nacional.cadastral.ddd.client.AnatelDddClient;
import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddResponse;
import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddSnapshot;
import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddSnapshot.DddEntry;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolve consultas de DDD sobre o snapshot da ANATEL cacheado.
 *
 * <p>O snapshot completo (~5.5k linhas) é baixado uma vez e mantido em
 * cache; o lookup por DDD agrupa as cidades em memória — operação O(N)
 * sobre lista cacheada, latência desprezível.</p>
 *
 * <p>RAC com hard-TTL 365d / soft 90d: mudanças no quadro de DDDs são
 * raras (última grande revisão em 2006); janela longa elimina round-trip
 * à ANATEL no hot path.</p>
 */
@Slf4j
@Service
public class DddService {

    private static final String CACHE_NAME = "ddd";
    private static final String CACHE_KEY = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(90);

    private final AnatelDddClient client;
    private final RefreshAheadCache refreshAheadCache;

    public DddService(AnatelDddClient client, RefreshAheadCache refreshAheadCache) {
        this.client = client;
        this.refreshAheadCache = refreshAheadCache;
    }

    public DddResponse findByDdd(String ddd) {
        DddSnapshot snapshot = loadSnapshot();

        // Filtra todas as entradas do DDD pedido. O state é o mesmo em todas
        // (assumido — historicamente um DDD pertence a uma única UF), mas
        // preservamos pelo primeiro hit para cobrir eventual exceção legada.
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
                client::fetchSnapshot);
    }
}
