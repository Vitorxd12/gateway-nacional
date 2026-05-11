package br.com.cernebr.gateway_nacional.financeiro.cvm.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cvm.client.BrasilApiCvmCorretorasClient;
import br.com.cernebr.gateway_nacional.financeiro.cvm.client.CvmCorretorasClient;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CorretoraResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CvmCorretorasSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Resolve consultas de corretoras CVM priorizando o download direto do
 * dump oficial; BrasilAPI entra como fallback quando o portal CVM está
 * instável.
 *
 * <h2>Cascata (ordem mandatória)</h2>
 * <ol>
 *   <li><b>Tier 1 — CVM direto:</b> {@link CvmCorretorasClient} baixa o
 *       {@code cad_intermed.zip} oficial e mantém um snapshot cacheado com
 *       RAC. Lookup por CNPJ opera em memória. Caminho normal e preferido.</li>
 *   <li><b>Tier 2 — BrasilAPI fallback:</b> {@link BrasilApiCvmCorretorasClient}
 *       acionado <em>somente</em> quando o tier 1 não consegue carregar
 *       (CVM fora + cache expirado). Para {@code findByCnpj}, BrasilAPI tem
 *       endpoint dedicado por CNPJ — não baixa lista inteira.</li>
 * </ol>
 *
 * <p><b>Diretriz mandatória:</b> a consulta direta à CVM nunca é pulada
 * em favor da BrasilAPI. O fallback existe para indisponibilidade real do
 * portal oficial durante o refresh do cache.</p>
 */
@Slf4j
@Service
public class CvmCorretorasService {

    private static final String CACHE_NAME = "cvmCorretoras";
    private static final String CACHE_KEY = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(7);

    private final CvmCorretorasClient cvmDirectClient;
    private final BrasilApiCvmCorretorasClient brasilApiFallbackClient;
    private final RefreshAheadCache refreshAheadCache;

    public CvmCorretorasService(CvmCorretorasClient cvmDirectClient,
                                BrasilApiCvmCorretorasClient brasilApiFallbackClient,
                                RefreshAheadCache refreshAheadCache) {
        this.cvmDirectClient = cvmDirectClient;
        this.brasilApiFallbackClient = brasilApiFallbackClient;
        this.refreshAheadCache = refreshAheadCache;
    }

    public List<CorretoraResponse> listAll() {
        try {
            return loadSnapshot().corretoras();
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("CVM corretoras snapshot interno indisponível ({}). Cascateando pra BrasilAPI fallback (listAll).",
                    tier1Failure.getMessage());
            try {
                return brasilApiFallbackClient.fetchSnapshot().corretoras();
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "listAll");
            }
        }
    }

    public CorretoraResponse findByCnpj(String cnpj) {
        String normalized = cnpj.replaceAll("\\D", "");
        CvmCorretorasSnapshot snapshot;
        try {
            snapshot = loadSnapshot();
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("CVM corretoras snapshot interno indisponível ({}). Cascateando pra BrasilAPI fallback (cnpj={}).",
                    tier1Failure.getMessage(), normalized);
            try {
                return brasilApiFallbackClient.findByCnpj(normalized);
            } catch (ResourceNotFoundException notFound) {
                throw notFound;
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "findByCnpj=" + normalized);
            }
        }

        return snapshot.corretoras().stream()
                .filter(c -> normalized.equals(c.cnpj()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Corretora",
                        "Corretora CVM com CNPJ " + cnpj + " não encontrada no snapshot."));
    }

    private CvmCorretorasSnapshot loadSnapshot() {
        return refreshAheadCache.get(CACHE_NAME, CACHE_KEY, SOFT_TTL,
                cvmDirectClient::fetchSnapshot);
    }

    private static ResourceUnavailableException unify(Throwable tier1, Throwable tier2, String context) {
        ResourceUnavailableException unified = new ResourceUnavailableException("cvm-corretoras",
                "CVM direto e BrasilAPI (fallback) falharam para " + context, tier2);
        unified.addSuppressed(tier1);
        return unified;
    }
}
