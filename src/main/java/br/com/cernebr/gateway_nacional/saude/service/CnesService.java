package br.com.cernebr.gateway_nacional.saude.service;

import br.com.cernebr.gateway_nacional.saude.client.CnesWebClient;
import br.com.cernebr.gateway_nacional.saude.dto.EstabelecimentoCnesResponse;
import br.com.cernebr.gateway_nacional.saude.dto.ProfissionalCnesResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates CNES profissionais retrieval. Cache TTL of 15 days mirrors
 * the federal payment cycle — cadastro changes are usually consolidated at
 * competency closure, so a 15-day window absorbs the typical update tempo.
 *
 * <p>Cache key composes both ibge and CNES because the upstream is keyed by
 * {@code {ibge}{cnes}} — caching by CNES alone would risk serving the wrong
 * municipality's profissionais if two cities use coincident CNES codes
 * (rare but documented).</p>
 */
@Slf4j
@Service
public class CnesService {

    private static final String DOMAIN = "saude";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private final CnesWebClient client;
    private final MeterRegistry meterRegistry;

    public CnesService(CnesWebClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "saude", key = "'cnes-' + #cnesBase + '-' + #ibge")
    public List<ProfissionalCnesResponse> findProfissionais(String cnesBase, String ibge) {
        String ibge6 = canonicalIbge(ibge);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<ProfissionalCnesResponse> profissionais = client.fetchProfissionais(cnesBase, ibge6);
            recordOutcome(client.providerName(), "success", sample);
            return profissionais;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("CNES provider failed for IBGE={} CNES={}: {}", ibge6, cnesBase, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Lista os estabelecimentos de saúde cadastrados no CNES para um
     * município. Pareada com {@link #findProfissionais(String, String)} para
     * compor o {@code TermometroApsService} sem exigir conhecimento prévio
     * de cada CNES.
     *
     * <p><b>Estado atual</b>: este método retorna um snapshot local
     * <i>determinístico</i> baseado no IBGE para que o orquestrador possa
     * ser homologado sem dependência do WAF do gov.br. O wiring real
     * apontará para
     * {@code cnes.datasus.gov.br/services/estabelecimentos?ibge={ibge6}},
     * roteado pelo mesmo {@code FlareSolverr} já usado por
     * {@code fetchProfissionais} — endpoint mapeado mas em backlog do
     * time de Saúde Pública.</p>
     *
     * <p>Cache de 15 dias mirra o de profissionais — cadastros de
     * estabelecimentos mudam no fechamento de competência mensal e
     * variações intra-mês não são confiáveis pra reportagem.</p>
     */
    @Cacheable(cacheNames = "saude", key = "'estabelecimentos-' + #ibge")
    public List<EstabelecimentoCnesResponse> listarEstabelecimentos(String ibge) {
        String ibge6 = canonicalIbge(ibge);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<EstabelecimentoCnesResponse> estabelecimentos = mockSnapshotPorIbge(ibge6);
            recordOutcome("CNES-Estab", "success", sample);
            log.info("CNES estabelecimentos resolved {} units for IBGE={}",
                    estabelecimentos.size(), ibge6);
            return estabelecimentos;
        } catch (RuntimeException ex) {
            recordOutcome("CNES-Estab", "failure", sample);
            log.warn("CNES estabelecimentos failed for IBGE={}: {}", ibge6, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Snapshot determinístico para homologação. Mistura intencionalmente
     * unidades APS (UBS/USF/UAPS) com não-APS (UPA, hospital, lab) de modo
     * que o orquestrador exercite a filtragem por {@code atencaoBasica} e
     * a derivação de {@code StatusRisco}.
     */
    private static List<EstabelecimentoCnesResponse> mockSnapshotPorIbge(String ibge6) {
        return List.of(
                new EstabelecimentoCnesResponse("2469776", "UBS Jardim Santa Inês — " + ibge6,
                        "UBS - Unidade Básica de Saúde", true),
                new EstabelecimentoCnesResponse("2469777", "USF Vila Andrade — " + ibge6,
                        "USF - Unidade de Saúde da Família", true),
                new EstabelecimentoCnesResponse("2469778", "UAPS Centro — " + ibge6,
                        "UAPS - Unidade de Atenção Primária à Saúde", true),
                new EstabelecimentoCnesResponse("9105200", "UPA 24h Distrito Norte — " + ibge6,
                        "UPA - Unidade de Pronto Atendimento", false),
                new EstabelecimentoCnesResponse("2076892", "Hospital Municipal Central — " + ibge6,
                        "Hospital Geral", false),
                new EstabelecimentoCnesResponse("3041820", "Laboratório Municipal — " + ibge6,
                        "Laboratório Central", false)
        );
    }

    private static String canonicalIbge(String ibge) {
        if (ibge == null) {
            throw new IllegalArgumentException("IBGE obrigatório.");
        }
        return ibge.length() >= 7 ? ibge.substring(0, 6) : ibge;
    }

    private void recordOutcome(String providerName, String outcome, Timer.Sample sample) {
        String providerTag = providerName.toLowerCase(Locale.ROOT);
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", providerTag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", providerTag,
                "outcome", outcome).increment();
    }
}
