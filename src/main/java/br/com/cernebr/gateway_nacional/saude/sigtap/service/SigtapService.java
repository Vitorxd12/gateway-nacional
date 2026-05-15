package br.com.cernebr.gateway_nacional.saude.sigtap.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapCboResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapCidResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapExportResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapProcedimentoResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapStatusResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapValorResumoResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.AuditoriaSigtapDTO;
import br.com.cernebr.gateway_nacional.saude.sigtap.config.SigtapProperties;
import br.com.cernebr.gateway_nacional.saude.sigtap.etl.SigtapEtlService;
import br.com.cernebr.gateway_nacional.saude.sigtap.jdbc.SigtapJdbc;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cid;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Dataset;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.DatasetStatus;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Procedimento;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static br.com.cernebr.gateway_nacional.config.CacheConfig.SIGTAP_CACHE;

/**
 * Camada de orquestração do SIGTAP.
 *
 * <p>Funde duas responsabilidades pequenas em uma fachada única:</p>
 * <ul>
 *   <li><b>Read path REST</b> — alimenta os endpoints de procedimento,
 *       cruzamentos CBO/CID, valores e exportação. Toda leitura passa
 *       pelo {@link RefreshAheadCache} no namespace
 *       {@code sigtap} (hard TTL 30d) para poupar I/O ao arquivo .db.</li>
 *   <li><b>Auditoria de compatibilidade</b> (legado) — mantém a rota
 *       {@code /auditoria} já documentada no front, agora alimentada
 *       pelas relações reais armazenadas no SQLite.</li>
 * </ul>
 *
 * <p><b>Resiliência ao módulo desligado:</b> o {@link SigtapJdbc} é
 * injetado via {@link ObjectProvider} porque é {@code @ConditionalOnProperty}.
 * Quando o flag está OFF, o provider devolve null e o service responde
 * 503 com mensagem clara — em vez de falhar no boot por dependência
 * ausente.</p>
 */
@Slf4j
@Service
public class SigtapService {

    private static final Duration SOFT_TTL = Duration.ofDays(7);

    private final ObjectProvider<SigtapJdbc> jdbcProvider;
    private final SigtapProperties props;
    private final ObjectProvider<SigtapEtlService> etlProvider;
    private final RefreshAheadCache cache;

    public SigtapService(ObjectProvider<SigtapJdbc> jdbcProvider,
                         SigtapProperties props,
                         ObjectProvider<SigtapEtlService> etlProvider,
                         RefreshAheadCache cache) {
        this.jdbcProvider = jdbcProvider;
        this.props = props;
        this.etlProvider = etlProvider;
        this.cache = cache;
    }


    // ──────────────────────────────────────────────────────────────────
    //  ZIP da competência ativa
    // ──────────────────────────────────────────────────────────────────

    /**
     * Localiza o arquivo .zip físico da competência ativa no workDir.
     * O ZIP é mantido após a ingestão e apagado apenas quando uma nova
     * competência é promovida com sucesso.
     */
    public Path getArquivoZipAtual() {
        DataAccess da = require();
        String filename = "TabelaUnificada_" + da.dataset.competencia() + "_v" + da.dataset.revisao() + ".zip";
        Path zip = Path.of(props.etl().workDir()).resolve(filename);

        if (!Files.exists(zip)) {
            throw new ResourceNotFoundException("SIGTAP",
                    "O arquivo ZIP da competência " + da.dataset.competencia()
                    + " não está disponível no servidor. Execute /atualizar para baixá-lo.");
        }
        return zip;
    }

    public SigtapStatusResponse status() {
        SigtapJdbc jdbc = jdbcProvider.getIfAvailable();

        SigtapStatusResponse.ConfiguracaoDTO config = new SigtapStatusResponse.ConfiguracaoDTO(
                props.cron().enabled(),
                props.cron().expression(),
                props.etl().workDir()
        );

        if (jdbc == null) {
            return new SigtapStatusResponse(config, null, null, List.of());
        }

        Optional<Dataset> active = jdbc.findActive();
        List<Dataset> history = jdbc.findRecentHistory(5);
        Optional<Dataset> lastAttempt = history.stream().findFirst();

        SigtapStatusResponse.BaseAtivaDTO baseAtiva = active.map(ds -> new SigtapStatusResponse.BaseAtivaDTO(
                ds.competencia(),
                ds.revisao(),
                ds.promotedAt(),
                jdbc.contarProcedimentos(ds.id()),
                ds.sourceUrl()
        )).orElse(null);

        SigtapStatusResponse.UltimaExecucaoDTO ultima = lastAttempt.map(ds -> new SigtapStatusResponse.UltimaExecucaoDTO(
                ds.startedAt(),
                ds.status().name(),
                ds.notes(),
                ds.status() == DatasetStatus.STAGING
        )).orElse(null);

        List<SigtapStatusResponse.HistoricoDatasetDTO> historico = history.stream()
                .map(ds -> new SigtapStatusResponse.HistoricoDatasetDTO(
                        ds.id(),
                        ds.competencia(),
                        ds.revisao(),
                        ds.status().name(),
                        ds.startedAt(),
                        ds.notes()
                )).toList();

        return new SigtapStatusResponse(config, baseAtiva, ultima, historico);
    }

    /**
     * Força a execução do ETL em background (ou síncrono, se preferir).
     * Aqui chamamos de forma síncrona para que o cliente saiba o resultado imediato.
     */
    public SigtapStatusResponse atualizar() {
        SigtapEtlService etl = etlProvider.getIfAvailable();
        if (etl == null) {
            throw new ResourceUnavailableException("SIGTAP",
                    "Motor de ETL desativado. Habilite GATEWAY_SIGTAP_CRON_ENABLED=true para atualizar.");
        }
        log.info("[SIGTAP] Gatilho manual de atualização acionado.");
        etl.executar();
        return status();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Procedimento detalhe
    // ──────────────────────────────────────────────────────────────────
    public SigtapProcedimentoResponse procedimento(String codigo) {
        return cache.get(SIGTAP_CACHE, "proc:" + codigo, SOFT_TTL, () -> {
            DataAccess da = require();
            Procedimento p = da.jdbc.findProcedimento(da.dataset.id(), codigo)
                    .orElseThrow(() -> new ResourceNotFoundException("SIGTAP",
                            "Procedimento " + codigo + " não consta na competência ativa " + da.dataset.competencia() + "."));
            return toResponse(p);
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  Procedimento busca
    // ──────────────────────────────────────────────────────────────────
    public List<SigtapProcedimentoResponse> buscarProcedimentos(String termo, int limit) {
        return cache.get(SIGTAP_CACHE, "search:" + termo.toLowerCase() + ":" + limit, SOFT_TTL, () -> {
            DataAccess da = require();
            return da.jdbc.buscarProcedimentos(da.dataset.id(), termo, limit).stream()
                    .map(this::toResponse).toList();
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  Cruzamentos CBO ↔ Procedimento
    // ──────────────────────────────────────────────────────────────────
    public List<SigtapProcedimentoResponse> procedimentosDoCbo(String cbo) {
        return cache.get(SIGTAP_CACHE, "cbo-proc:" + cbo, SOFT_TTL, () -> {
            DataAccess da = require();
            List<String> codigos = da.jdbc.procedimentosDoCbo(da.dataset.id(), cbo);
            return codigos.stream()
                    .map(c -> da.jdbc.findProcedimento(da.dataset.id(), c))
                    .filter(Optional::isPresent).map(Optional::get)
                    .map(this::toResponse).toList();
        });
    }

    public List<SigtapCboResponse> cbosDoProcedimento(String codigo) {
        return cache.get(SIGTAP_CACHE, "proc-cbo:" + codigo, SOFT_TTL, () -> {
            DataAccess da = require();
            List<String> codigos = da.jdbc.cbosDoProcedimento(da.dataset.id(), codigo);
            return codigos.stream()
                    .map(c -> da.jdbc.findCbo(da.dataset.id(), c))
                    .filter(Optional::isPresent).map(Optional::get)
                    .map(c -> new SigtapCboResponse(c.codigo(), c.nome())).toList();
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  Cruzamentos CID ↔ Procedimento
    // ──────────────────────────────────────────────────────────────────
    public List<SigtapProcedimentoResponse> procedimentosDoCid(String cid) {
        return cache.get(SIGTAP_CACHE, "cid-proc:" + cid, SOFT_TTL, () -> {
            DataAccess da = require();
            List<String> codigos = da.jdbc.procedimentosDoCid(da.dataset.id(), cid);
            return codigos.stream()
                    .map(c -> da.jdbc.findProcedimento(da.dataset.id(), c))
                    .filter(Optional::isPresent).map(Optional::get)
                    .map(this::toResponse).toList();
        });
    }

    public List<SigtapCidResponse> cidsDoProcedimento(String codigo) {
        return cache.get(SIGTAP_CACHE, "proc-cid:" + codigo, SOFT_TTL, () -> {
            DataAccess da = require();
            List<ProcedimentoCid> rels = da.jdbc.cidsDoProcedimento(da.dataset.id(), codigo);
            return rels.stream().map(rel -> {
                Cid cid = da.jdbc.findCid(da.dataset.id(), rel.cidCodigo()).orElse(null);
                return new SigtapCidResponse(rel.cidCodigo(),
                        cid != null ? cid.nome() : null,
                        rel.obrigatorio());
            }).toList();
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  Valores / ranking financeiro
    // ──────────────────────────────────────────────────────────────────
    public List<SigtapValorResumoResponse> rankingValores(String grupo, String ordenar, int limit) {
        boolean asc = "menor_valor".equalsIgnoreCase(ordenar);
        String key = "rank:" + (grupo == null ? "all" : grupo) + ":" + (asc ? "asc" : "desc") + ":" + limit;
        return cache.get(SIGTAP_CACHE, key, SOFT_TTL, () -> {
            DataAccess da = require();
            return da.jdbc.rankProcedimentosPorValor(da.dataset.id(), grupo, asc, limit).stream()
                    .map(p -> new SigtapValorResumoResponse(
                            p.codigo(), p.nome(), p.grupoCodigo(),
                            p.valorSa(), p.valorSh(), p.valorSp(), p.valorTotal()))
                    .toList();
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  Dump completo (exportação paginada)
    // ──────────────────────────────────────────────────────────────────
    public SigtapExportResponse exportarMesAtual(int page, int size) {
        DataAccess da = require();
        long datasetId = da.dataset.id();

        int totalProcedimentos = da.jdbc.contarProcedimentos(datasetId);
        int totalPaginas = (int) Math.ceil((double) totalProcedimentos / size);

        // Busca apenas o recorte da página para procedimentos
        List<Procedimento> procs = da.jdbc.listProcedimentosPaginado(datasetId, page, size);
        List<String> procCodigos = procs.stream().map(Procedimento::codigo).toList();

        // Mapas auxiliares (recortados apenas para o que aparece nesta página para economizar memória)
        Map<String, String> cboMap = new LinkedHashMap<>();
        Map<String, String> cidMap = new LinkedHashMap<>();
        Map<String, List<String>> procCboMap = new LinkedHashMap<>();
        Map<String, List<String>> procCidMap = new LinkedHashMap<>();

        for (Procedimento p : procs) {
            String pCod = p.codigo();

            // CBOs do procedimento
            List<String> cbos = da.jdbc.cbosDoProcedimento(datasetId, pCod);
            if (!cbos.isEmpty()) {
                procCboMap.put(pCod, cbos);
                for (String cboCod : cbos) {
                    da.jdbc.findCbo(datasetId, cboCod).ifPresent(c -> cboMap.put(c.codigo(), c.nome()));
                }
            }

            // CIDs do procedimento
            List<ProcedimentoCid> cids = da.jdbc.cidsDoProcedimento(datasetId, pCod);
            if (!cids.isEmpty()) {
                List<String> cidCodigos = cids.stream().map(ProcedimentoCid::cidCodigo).toList();
                procCidMap.put(pCod, cidCodigos);
                for (String cidCod : cidCodigos) {
                    da.jdbc.findCid(datasetId, cidCod).ifPresent(c -> cidMap.put(c.codigo(), c.nome()));
                }
            }
        }

        return new SigtapExportResponse(
                da.dataset.competencia(),
                OffsetDateTime.now(ZoneOffset.UTC),
                page,
                size,
                totalPaginas,
                totalProcedimentos,
                procs.stream().map(this::toResponse).toList(),
                cboMap,
                cidMap,
                procCboMap,
                procCidMap
        );
    }

    // ──────────────────────────────────────────────────────────────────
    //  Auditoria legacy (compativel/incompativel CBO x procedimento)
    // ──────────────────────────────────────────────────────────────────
    public AuditoriaSigtapDTO auditarProcedimento(String ibge, String cbo, String procedimento) {
        SigtapJdbc jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return new AuditoriaSigtapDTO(ibge, cbo, procedimento, null,
                    "Módulo SIGTAP desativado — habilite GATEWAY_SIGTAP_CRON_ENABLED=true.",
                    "sigtap-desativado");
        }
        Optional<Dataset> active = jdbc.findActive();
        if (active.isEmpty()) {
            return new AuditoriaSigtapDTO(ibge, cbo, procedimento, null,
                    "Aguardando primeira ingestão SIGTAP do DataSUS.", "sigtap-vazio");
        }
        boolean compativel = jdbc.cbosDoProcedimento(active.get().id(), procedimento).contains(cbo);
        String justificativa = compativel
                ? "CBO autorizado a faturar este procedimento na competência " + active.get().competencia()
                : "CBO não autorizado para este procedimento — risco de glosa.";
        return new AuditoriaSigtapDTO(ibge, cbo, procedimento, compativel, justificativa,
                "SIGTAP-SQLite/" + active.get().competencia());
    }

    // ──────────────────────────────────────────────────────────────────
    //  Helpers internos
    // ──────────────────────────────────────────────────────────────────
    private record DataAccess(SigtapJdbc jdbc, Dataset dataset) {
    }

    private DataAccess require() {
        SigtapJdbc jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new ResourceUnavailableException("SIGTAP",
                    "Módulo SIGTAP desativado — habilite GATEWAY_SIGTAP_CRON_ENABLED=true para usar.");
        }
        Dataset active = jdbc.findActive().orElseThrow(() -> new ResourceUnavailableException("SIGTAP",
                "Nenhuma competência ativa — aguardando primeira ingestão do DataSUS."));
        return new DataAccess(jdbc, active);
    }

    private SigtapProcedimentoResponse toResponse(Procedimento p) {
        return new SigtapProcedimentoResponse(
                p.codigo(), p.nome(), p.complexidade(), p.sexo(),
                p.idadeMinimaDias(), p.idadeMaximaDias(),
                p.quantidadeMaxima(), p.tipoFinanciamento(),
                p.valorSa(), p.valorSh(), p.valorSp(),
                p.valorTotal(),
                p.grupoCodigo(), p.subgrupoCodigo(), p.formaOrganizacaoCodigo(),
                p.dtCompetencia()
        );
    }
}
