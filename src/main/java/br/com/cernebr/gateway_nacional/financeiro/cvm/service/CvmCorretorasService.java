package br.com.cernebr.gateway_nacional.financeiro.cvm.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.financeiro.cvm.client.CvmCorretorasClient;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CorretoraResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CvmCorretorasSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Resolve consultas de corretoras CVM sobre um <b>snapshot único</b> baixado
 * do CVM e cacheado inteiro. As duas operações públicas
 * ({@link #listAll()}, {@link #findByCnpj(String)}) operam em memória sobre a
 * lista cacheada — não há round-trip no caminho crítico após o primeiro
 * carregamento.
 *
 * <p><b>Single-provider de propósito:</b> a CVM é a única fonte canônica
 * dos dados de intermediários autorizados. Não existe segundo provider
 * equivalente — qualquer terceiro provider seria um espelho do mesmo dump.
 * Hedge não se aplica; mantemos cascata de tamanho 1.</p>
 *
 * <p>RAC com hard-TTL 30d / soft 7d: a CVM atualiza o {@code cad_intermed.zip}
 * mensalmente. 7d de soft-TTL dispara refresh oportunista entre janelas, sem
 * desperdiçar download para chaves frias (clientes que consultam uma corretora
 * só uma vez não merecem recarga semanal).</p>
 */
@Slf4j
@Service
public class CvmCorretorasService {

    private static final String CACHE_NAME = "cvmCorretoras";
    private static final String CACHE_KEY = "snapshot";
    private static final Duration SOFT_TTL = Duration.ofDays(7);

    private final CvmCorretorasClient client;
    private final RefreshAheadCache refreshAheadCache;

    public CvmCorretorasService(CvmCorretorasClient client,
                                RefreshAheadCache refreshAheadCache) {
        this.client = client;
        this.refreshAheadCache = refreshAheadCache;
    }

    public List<CorretoraResponse> listAll() {
        return loadSnapshot().corretoras();
    }

    public CorretoraResponse findByCnpj(String cnpj) {
        String normalized = cnpj.replaceAll("\\D", "");
        return loadSnapshot().corretoras().stream()
                .filter(c -> normalized.equals(c.cnpj()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Corretora",
                        "Corretora CVM com CNPJ " + cnpj + " não encontrada no snapshot."));
    }

    private CvmCorretorasSnapshot loadSnapshot() {
        return refreshAheadCache.get(CACHE_NAME, CACHE_KEY, SOFT_TTL,
                client::fetchSnapshot);
    }
}
