package br.com.cernebr.gateway_nacional.financeiro.cvm.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
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
 * Resolve consultas de fundos CVM sobre snapshot cacheado. Mesma estratégia
 * do {@link CvmCorretorasService}: snapshot único baixado e mantido em cache;
 * paginação e lookup por CNPJ operam em memória.
 *
 * <p>{@link #listPaginated(int, int)} aplica os mesmos limites da BrasilAPI:
 * tamanho máximo 200 por página (proteção contra request abusiva — listar 30k
 * fundos em uma única response seria ~30MB de JSON).</p>
 *
 * <p>RAC: hard-TTL 30d / soft 7d, alinhado com a cadência de publicação
 * mensal do {@code cad_fi.csv}.</p>
 */
@Slf4j
@Service
public class CvmFundosService {

    private static final String CACHE_NAME = "cvmFundos";
    private static final String CACHE_KEY = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(7);

    /** Limite herdado da BrasilAPI — proteção contra response gigante. */
    public static final int MAX_PAGE_SIZE = 200;

    private final CvmFundosClient client;
    private final RefreshAheadCache refreshAheadCache;

    public CvmFundosService(CvmFundosClient client,
                            RefreshAheadCache refreshAheadCache) {
        this.client = client;
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

        List<FundoDetailResponse> all = loadSnapshot().fundos();
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
        return loadSnapshot().fundos().stream()
                .filter(f -> normalized.equals(f.cnpj()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Fundo",
                        "Fundo CVM com CNPJ " + cnpj + " não encontrado no snapshot."));
    }

    private CvmFundosSnapshot loadSnapshot() {
        return refreshAheadCache.get(CACHE_NAME, CACHE_KEY, SOFT_TTL,
                client::fetchSnapshot);
    }
}
