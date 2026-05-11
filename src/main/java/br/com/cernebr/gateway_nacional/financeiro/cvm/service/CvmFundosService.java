package br.com.cernebr.gateway_nacional.financeiro.cvm.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cvm.client.BrasilApiCvmFundosClient;
import br.com.cernebr.gateway_nacional.financeiro.cvm.client.CvmFundosClient;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CvmFundosSnapshot;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundoDetailResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundoSummaryResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundosPageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Resolve consultas de fundos CVM priorizando o download direto do
 * {@code cad_fi.csv}; BrasilAPI entra como fallback granular (por operação,
 * não snapshot completo).
 *
 * <h2>Cascata (ordem mandatória)</h2>
 * <ol>
 *   <li><b>Tier 1 — CVM direto:</b> {@link CvmFundosClient} baixa o CSV de
 *       ~10MB com 30k fundos e o mantém em snapshot cacheado via RAC.
 *       Paginação e lookup por CNPJ operam em memória — O(N) sobre lista
 *       cacheada. Caminho normal.</li>
 *   <li><b>Tier 2 — BrasilAPI granular:</b> {@link BrasilApiCvmFundosClient}
 *       acionado por operação quando o snapshot interno não consegue
 *       carregar (CVM fora + cache expirado). Não replica o snapshot
 *       inteiro — usa os endpoints paginado/por-CNPJ da BrasilAPI direto.
 *       Modo degradado: cada request vira round-trip, mas o serviço
 *       continua respondendo.</li>
 * </ol>
 *
 * <p><b>Diretriz mandatória:</b> a consulta direta à CVM nunca é pulada
 * em favor da BrasilAPI. Fallback existe pra indisponibilidade real.</p>
 *
 * <p>{@link #listPaginated} aplica limite 200 por página — proteção contra
 * response gigante quando vier do snapshot interno (30MB se listássemos tudo).</p>
 */
@Slf4j
@Service
public class CvmFundosService {

    private static final String CACHE_NAME = "cvmFundos";
    private static final String CACHE_KEY = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(7);

    /** Limite herdado da BrasilAPI — proteção contra response gigante. */
    public static final int MAX_PAGE_SIZE = 200;

    private final CvmFundosClient cvmDirectClient;
    private final BrasilApiCvmFundosClient brasilApiFallbackClient;
    private final RefreshAheadCache refreshAheadCache;

    public CvmFundosService(CvmFundosClient cvmDirectClient,
                            BrasilApiCvmFundosClient brasilApiFallbackClient,
                            RefreshAheadCache refreshAheadCache) {
        this.cvmDirectClient = cvmDirectClient;
        this.brasilApiFallbackClient = brasilApiFallbackClient;
        this.refreshAheadCache = refreshAheadCache;
    }

    public FundosPageResponse listPaginated(int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page deve ser >= 1, recebido: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size deve estar entre 1 e " + MAX_PAGE_SIZE + ", recebido: " + size);
        }

        List<FundoDetailResponse> all;
        try {
            all = loadSnapshot().fundos();
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("CVM fundos snapshot interno indisponível ({}). Cascateando pra BrasilAPI fallback (page={} size={}).",
                    tier1Failure.getMessage(), page, size);
            try {
                return brasilApiFallbackClient.fetchPage(size, page);
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "listPaginated page=" + page + " size=" + size);
            }
        }

        // Tier 1 OK — pagina em memória sobre o snapshot.
        int total = all.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);

        List<FundoSummaryResponse> pageData = all.subList(from, to).stream()
                .map(f -> new FundoSummaryResponse(
                        f.cnpj(),
                        f.denominacaoSocial(),
                        f.codigoCvm(),
                        f.tipoFundo(),
                        f.situacao()))
                .toList();

        return new FundosPageResponse(size, page, total, pageData);
    }

    public FundoDetailResponse findByCnpj(String cnpj) {
        String normalized = cnpj.replaceAll("\\D", "");
        CvmFundosSnapshot snapshot;
        try {
            snapshot = loadSnapshot();
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("CVM fundos snapshot interno indisponível ({}). Cascateando pra BrasilAPI fallback (cnpj={}).",
                    tier1Failure.getMessage(), normalized);
            try {
                return brasilApiFallbackClient.findByCnpj(normalized);
            } catch (ResourceNotFoundException notFound) {
                throw notFound;
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "findByCnpj=" + normalized);
            }
        }

        return snapshot.fundos().stream()
                .filter(f -> normalized.equals(f.cnpj()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Fundo",
                        "Fundo CVM com CNPJ " + cnpj + " não encontrado no snapshot."));
    }

    private CvmFundosSnapshot loadSnapshot() {
        return refreshAheadCache.get(CACHE_NAME, CACHE_KEY, SOFT_TTL,
                cvmDirectClient::fetchSnapshot);
    }

    private static ResourceUnavailableException unify(Throwable tier1, Throwable tier2, String context) {
        ResourceUnavailableException unified = new ResourceUnavailableException("cvm-fundos",
                "CVM direto e BrasilAPI (fallback) falharam para " + context, tier2);
        unified.addSuppressed(tier1);
        return unified;
    }
}
