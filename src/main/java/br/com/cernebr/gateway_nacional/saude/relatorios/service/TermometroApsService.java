package br.com.cernebr.gateway_nacional.saude.relatorios.service;

import br.com.cernebr.gateway_nacional.saude.dto.EstabelecimentoCnesResponse;
import br.com.cernebr.gateway_nacional.saude.indicadores.dto.IndicadorSinteticoResponse;
import br.com.cernebr.gateway_nacional.saude.indicadores.dto.MetricaIndicador;
import br.com.cernebr.gateway_nacional.saude.indicadores.service.SisabIndicadoresService;
import br.com.cernebr.gateway_nacional.saude.relatorios.dto.RelatorioDesempenhoApsResponse;
import br.com.cernebr.gateway_nacional.saude.relatorios.dto.UnidadeAlertaDTO;
import br.com.cernebr.gateway_nacional.saude.relatorios.dto.UnidadeAlertaDTO.StatusRisco;
import br.com.cernebr.gateway_nacional.saude.service.CnesService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orquestrador do "Termômetro APS" para a gestão municipal — cruza a nota
 * Previne Brasil/PMA do {@link SisabIndicadoresService} com os
 * estabelecimentos APS do {@link CnesService} para entregar uma lista
 * priorizada de unidades que precisam de busca ativa.
 *
 * <p><b>ATENÇÃO: Não migrar para
 * {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor} nem
 * {@link br.com.cernebr.gateway_nacional.config.RefreshAheadCache}.</b>
 * Orquestrador de dois services pesados (sidecar SISAB + scraper CNES). Os
 * dois colaboradores já têm caches Redis próprios; o fan-out em virtual
 * threads já paraleliza. Hedging não se aplica (não há providers equivalentes
 * a paralelizar — são domínios distintos compostos), e RAC duplicaria o
 * trabalho pesado dos downstreams em background. Mantém composição atual.</p>
 *
 * <h2>Por que sem {@code @Cacheable} aqui</h2>
 * <p>Os dois colaboradores já têm caches Redis próprios (30 dias para
 * indicadores SISAB no namespace {@code indicadoresAps}, 15 dias para
 * estabelecimentos CNES no namespace {@code saude}). Adicionar uma terceira
 * camada de cache no agregador duplicaria armazenamento e tornaria a
 * invalidação cirúrgica difícil — a entrada agregada teria TTL próprio
 * que poderia divergir das fontes.</p>
 *
 * <h2>Paralelismo</h2>
 * <p>SISAB e CNES são chamadas independentes — qualquer ordem produz o
 * mesmo resultado. Disparadas via {@code Executors.newVirtualThreadPerTaskExecutor()}
 * em try-with-resources: o overhead de criar uma virtual thread é da ordem
 * de microssegundos, e o shutdown determinístico garante que nenhuma
 * thread vaze entre requisições.</p>
 *
 * <h2>Propagação de exceções</h2>
 * <p>Se SISAB falhar com {@code ResourceUnavailableException} (sidecar
 * down, DataSUS recusou conexão), a 503 sobe limpa para o cliente — o
 * {@code joinUnwrapped} desembrulha o {@code CompletionException} para
 * preservar o tipo e mensagem original que o handler global já sabe
 * formatar. Mesma lógica do {@code AuditoriaSaudeService}.</p>
 */
@Slf4j
@Service
public class TermometroApsService {

    private static final String DOMAIN = "saude";
    private static final String AGGREGATOR = "termometroAps";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    /** Limiar abaixo do qual a unidade APS recebe alerta URGENTE (busca ativa imediata). */
    private static final BigDecimal LIMIAR_URGENTE = BigDecimal.valueOf(6.0);

    private final SisabIndicadoresService sisabIndicadoresService;
    private final CnesService cnesService;
    private final MeterRegistry meterRegistry;

    public TermometroApsService(SisabIndicadoresService sisabIndicadoresService,
                                CnesService cnesService,
                                MeterRegistry meterRegistry) {
        this.sisabIndicadoresService = sisabIndicadoresService;
        this.cnesService = cnesService;
        this.meterRegistry = meterRegistry;
    }

    public RelatorioDesempenhoApsResponse build(String ibge, String quadrimestre) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RelatorioDesempenhoApsResponse response = doBuild(ibge, quadrimestre);
            recordOutcome("success", sample);
            log.info("TermometroAPS computed ibge={} quadrimestre={} unidades={} (urgentes={})",
                    ibge, quadrimestre, response.unidadesAlerta().size(),
                    countByStatus(response.unidadesAlerta(), StatusRisco.URGENTE));
            return response;
        } catch (RuntimeException ex) {
            recordOutcome("failure", sample);
            log.warn("TermometroAPS failed ibge={} quadrimestre={}: {}", ibge, quadrimestre, ex.getMessage());
            throw ex;
        }
    }

    private RelatorioDesempenhoApsResponse doBuild(String ibge, String quadrimestre) {
        IndicadorSinteticoResponse indicadores;
        List<EstabelecimentoCnesResponse> estabelecimentos;

        long t0 = System.currentTimeMillis();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            log.info("TermometroAPS disparando SISAB+CNES em paralelo (virtual threads)");

            CompletableFuture<IndicadorSinteticoResponse> fIndicadores =
                    CompletableFuture.supplyAsync(
                            () -> sisabIndicadoresService.consultar(ibge, quadrimestre), executor);
            CompletableFuture<List<EstabelecimentoCnesResponse>> fEstabs =
                    CompletableFuture.supplyAsync(
                            () -> cnesService.listarEstabelecimentos(ibge), executor);

            indicadores = joinUnwrapped(fIndicadores);
            estabelecimentos = joinUnwrapped(fEstabs);
        }
        long tParalelo = System.currentTimeMillis() - t0;
        log.info("TermometroAPS SISAB+CNES concluídos em {}ms (paralelo)", tParalelo);

        return assemble(ibge, indicadores, estabelecimentos);
    }

    /**
     * Compõe o relatório final. A lista é filtrada para apenas
     * estabelecimentos da Atenção Básica (postos de saúde) — a única
     * subpopulação que entra no cálculo do PAB-Variável e portanto na
     * heurística de risco. UPAs, hospitais e laboratórios são ignorados.
     */
    private RelatorioDesempenhoApsResponse assemble(String ibge,
                                                    IndicadorSinteticoResponse indicadores,
                                                    List<EstabelecimentoCnesResponse> estabelecimentos) {
        List<String> defasados = derivarDefasados(indicadores.metricas());
        StatusRisco status = derivarStatus(indicadores);
        String observacao = construirObservacao(status, defasados);

        List<UnidadeAlertaDTO> unidades = estabelecimentos.stream()
                .filter(EstabelecimentoCnesResponse::atencaoBasica)
                .map(estab -> new UnidadeAlertaDTO(
                        estab.cnes(),
                        estab.nome(),
                        estab.tipoUnidade(),
                        status,
                        defasados,
                        observacao
                ))
                .toList();

        RelatorioDesempenhoApsResponse.Municipio municipio = new RelatorioDesempenhoApsResponse.Municipio(
                ibge,
                resolveNomeMunicipio(ibge),
                ufFromIbge(ibge)
        );

        return new RelatorioDesempenhoApsResponse(
                municipio,
                indicadores.quadrimestre(),
                indicadores.notaFinal(),
                Boolean.TRUE.equals(indicadores.metaAlcancada()),
                unidades
        );
    }

    /**
     * Heurística:
     * <ul>
     *   <li>{@code notaFinal < 6.0} → URGENTE (independente da meta);</li>
     *   <li>{@code !metaAlcancada} com nota ≥ 6.0 → ATENCAO;</li>
     *   <li>{@code metaAlcancada} → OK.</li>
     * </ul>
     */
    private static StatusRisco derivarStatus(IndicadorSinteticoResponse indicadores) {
        if (indicadores.notaFinal() != null
                && indicadores.notaFinal().compareTo(LIMIAR_URGENTE) < 0) {
            return StatusRisco.URGENTE;
        }
        if (!Boolean.TRUE.equals(indicadores.metaAlcancada())) {
            return StatusRisco.ATENCAO;
        }
        return StatusRisco.OK;
    }

    /**
     * Indicador "defasado" = {@code percentualAtual < percentualMeta}. O
     * Previne Brasil define a meta por indicador, e o SISAB devolve ambos
     * — o cruzamento aqui sobrevive a mudanças de meta sem alterar a regra.
     */
    private static List<String> derivarDefasados(List<MetricaIndicador> metricas) {
        if (metricas == null) return List.of();
        return metricas.stream()
                .filter(m -> m.percentualAtual() != null && m.percentualMeta() != null)
                .filter(m -> m.percentualAtual().compareTo(m.percentualMeta()) < 0)
                .map(MetricaIndicador::nomeIndicador)
                .toList();
    }

    private static String construirObservacao(StatusRisco status, List<String> defasados) {
        return switch (status) {
            case URGENTE -> defasados.isEmpty()
                    ? "Disparar busca ativa imediata — nota geral abaixo de 6.0."
                    : "Disparar busca ativa imediata: " + defasados.size()
                    + " indicador(es) defasado(s) (" + String.join(", ", defasados) + ").";
            case ATENCAO -> defasados.isEmpty()
                    ? "Acompanhar — meta não alcançada apesar da nota acima do piso de 6.0."
                    : "Acompanhar — meta não alcançada (defasados: " + String.join(", ", defasados) + ").";
            case OK -> "Manter cadência atual — meta alcançada no quadrimestre.";
        };
    }

    private static long countByStatus(List<UnidadeAlertaDTO> unidades, StatusRisco status) {
        return unidades.stream().filter(u -> u.statusRisco() == status).count();
    }

    /**
     * Tabela mínima para o teste local — em produção, esta resolução
     * delegará ao módulo {@code cadastral.municipio} (em backlog do time
     * de cadastros). Mantém o relatório auto-contido enquanto o lookup
     * canônico não está pronto.
     */
    private static String resolveNomeMunicipio(String ibge) {
        return MUNICIPIOS_CONHECIDOS.getOrDefault(ibge, "Município " + ibge);
    }

    private static final Map<String, String> MUNICIPIOS_CONHECIDOS = Map.ofEntries(
            Map.entry("355030", "São Paulo"),
            Map.entry("3550308", "São Paulo"),
            Map.entry("330455", "Rio de Janeiro"),
            Map.entry("3304557", "Rio de Janeiro"),
            Map.entry("292740", "Salvador"),
            Map.entry("2927408", "Salvador"),
            Map.entry("230440", "Fortaleza"),
            Map.entry("2304400", "Fortaleza")
    );

    /** Réplica enxuta de {@code IbgeUfLookup} (que é package-private em saude/client). */
    private static String ufFromIbge(String ibge) {
        if (ibge == null || ibge.length() < 2) return null;
        return UF_POR_PREFIXO.get(ibge.substring(0, 2));
    }

    private static final Map<String, String> UF_POR_PREFIXO = Map.ofEntries(
            Map.entry("11", "RO"), Map.entry("12", "AC"), Map.entry("13", "AM"),
            Map.entry("14", "RR"), Map.entry("15", "PA"), Map.entry("16", "AP"),
            Map.entry("17", "TO"), Map.entry("21", "MA"), Map.entry("22", "PI"),
            Map.entry("23", "CE"), Map.entry("24", "RN"), Map.entry("25", "PB"),
            Map.entry("26", "PE"), Map.entry("27", "AL"), Map.entry("28", "SE"),
            Map.entry("29", "BA"), Map.entry("31", "MG"), Map.entry("32", "ES"),
            Map.entry("33", "RJ"), Map.entry("35", "SP"), Map.entry("41", "PR"),
            Map.entry("42", "SC"), Map.entry("43", "RS"), Map.entry("50", "MS"),
            Map.entry("51", "MT"), Map.entry("52", "GO"), Map.entry("53", "DF")
    );

    /**
     * Mesma estratégia do {@code AuditoriaSaudeService}: desembrulha
     * {@link CompletionException} para que a exceção original
     * (ResourceUnavailableException etc.) suba limpa ao
     * {@code GlobalExceptionHandler}.
     */
    private static <T> T joinUnwrapped(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw ce;
        }
    }

    private void recordOutcome(String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", AGGREGATOR)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", AGGREGATOR,
                "outcome", outcome).increment();
    }
}
