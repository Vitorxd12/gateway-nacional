package br.com.cernebr.gateway_nacional.cadastral.sintegra.service;

import br.com.cernebr.gateway_nacional.cadastral.sintegra.client.SintegraAgregadorFallbackClient;
import br.com.cernebr.gateway_nacional.cadastral.sintegra.client.SintegraCccClient;
import br.com.cernebr.gateway_nacional.cadastral.sintegra.client.SintegraClient;
import br.com.cernebr.gateway_nacional.cadastral.sintegra.dto.SintegraResponse;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orquestrador do Sintegra / Inscrição Estadual.
 *
 * <p><b>Estratégia hedge com "empty as failure":</b> CCC/SVRS e agregador
 * cobrem conjuntos de UFs parcialmente disjuntos (CCC ignora SP/MG/RJ).
 * Disparar em paralelo garante o melhor entre os dois. Para isso, "empty"
 * de um provedor é convertido em {@link ResourceUnavailableException} antes
 * do hedge — assim o {@link HedgedExecutor} segue esperando o outro em vez
 * de retornar um 404 prematuro do mais rápido. Se <em>ambos</em> falharem
 * (incluindo empty), o agregador final lança e o service traduz para 404
 * quando o motivo predominante foi empty (não 503).</p>
 *
 * <p><b>Trade-off:</b> sob hedge, ambos os provedores são sempre chamados —
 * paga-se a banda do agregador (mais lento, mais caro) mesmo quando o CCC
 * teria respondido sozinho. Aceitável aqui porque cada chamada Sintegra é
 * rara (raramente cacheada por menos de dias) e o ganho de cobertura
 * supera o custo bruto.</p>
 */
@Slf4j
@Service
public class SintegraService {

    private static final String DOMAIN = "sintegra";
    private static final String CACHE_NAME = "sintegra";
    private static final Duration SOFT_TTL = Duration.ofDays(2);

    private final SintegraCccClient ccc;
    private final SintegraAgregadorFallbackClient agregador;
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public SintegraService(SintegraCccClient ccc,
                           SintegraAgregadorFallbackClient agregador,
                           HedgedExecutor hedgedExecutor,
                           RefreshAheadCache refreshAheadCache) {
        this.ccc = ccc;
        this.agregador = agregador;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    public SintegraResponse findByCnpj(String cnpj, String uf) {
        String cacheKey = uf == null || uf.isBlank() ? cnpj : cnpj + ":" + uf;
        return refreshAheadCache.get(CACHE_NAME, cacheKey, SOFT_TTL, () -> loadFromProviders(cnpj, uf));
    }

    private SintegraResponse loadFromProviders(String cnpj, String uf) {
        try {
            return hedgedExecutor.anyOf(DOMAIN, List.of(
                    new NamedSupplier<>(ccc.providerName(), throwIfEmpty(ccc, cnpj, uf)),
                    new NamedSupplier<>(agregador.providerName(), throwIfEmpty(agregador, cnpj, uf))
            ));
        } catch (ResourceUnavailableException ex) {
            // Distingue "indisponível" de "não encontrado" examinando a causa:
            // se a última falha foi por empty, é 404; se foi por rede/5xx, mantém 503.
            if (isLastCauseEmpty(ex)) {
                throw new ResourceNotFoundException(DOMAIN,
                        "Nenhuma inscrição estadual localizada para o CNPJ "
                                + cnpj + (uf != null ? " na UF " + uf : " em qualquer UF."));
            }
            throw ex;
        }
    }

    private static Supplier<SintegraResponse> throwIfEmpty(SintegraClient client, String cnpj, String uf) {
        return () -> {
            Optional<SintegraResponse> result = client.fetch(cnpj, uf);
            return result.orElseThrow(() -> new ResourceUnavailableException(client.providerName(),
                    "EMPTY: provedor respondeu mas não encontrou IE para CNPJ na UF — sinaliza ao hedge para esperar o outro."));
        };
    }

    private static boolean isLastCauseEmpty(Throwable t) {
        Throwable cause = t.getCause();
        return cause != null && cause.getMessage() != null && cause.getMessage().startsWith("EMPTY:");
    }
}
