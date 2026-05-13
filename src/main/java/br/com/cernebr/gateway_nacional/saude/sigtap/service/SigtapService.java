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
import br.com.cernebr.gateway_nacional.saude.sigtap.jdbc.SigtapJdbc;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cid;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Dataset;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Procedimento;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
    private final ObjectProvider<SigtapProperties> propsProvider;
    private final RefreshAheadCache cache;

    public SigtapService(ObjectProvider<SigtapJdbc> jdbcProvider,
                         ObjectProvider<SigtapProperties> propsProvider,
                         RefreshAheadCache cache) {
        this.jdbcProvider = jdbcProvider;
        this.propsProvider = propsProvider;
        this.cache = cache;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Status (sempre disponível, mesmo desabilitado)
    // ──────────────────────────────────────────────────────────────────
    public SigtapStatusResponse status() {
        SigtapJdbc jdbc = jdbcProvider.getIfAvailable();
        SigtapProperties props = propsProvider.getIfAvailable();
        if (jdbc == null || props == null) {
            return new SigtapStatusResponse(null, null, false, false,
                    "(desativado — habilite GATEWAY_SIGTAP_CRON_ENABLED=true)", null);
        }
        Optional<Dataset> active = jdbc.findActive();
        boolean staging = jdbc.findStaging().isPresent();
        return new SigtapStatusResponse(
                active.map(Dataset::competencia).orElse(null),
                active.map(Dataset::promotedAt).orElse(null),
                staging,
                props.cron().enabled(),
                props.cron().expression(),
                active.map(Dataset::sourceUrl).orElse(null));
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
    //  Dump completo (exportação)
    // ──────────────────────────────────────────────────────────────────
    public SigtapExportResponse exportarMesAtual() {
        DataAccess da = require();
        long datasetId = da.dataset.id();

        List<Procedimento> procs = da.jdbc.listAllProcedimentos(datasetId);
        List<Cbo> cbos = da.jdbc.listAllCbos(datasetId);
        List<Cid> cids = da.jdbc.listAllCids(datasetId);
        List<ProcedimentoCbo> relCbo = da.jdbc.listAllProcCbo(datasetId);
        List<ProcedimentoCid> relCid = da.jdbc.listAllProcCid(datasetId);

        Map<String, String> cboMap = new LinkedHashMap<>();
        cbos.forEach(c -> cboMap.put(c.codigo(), c.nome()));
        Map<String, String> cidMap = new LinkedHashMap<>();
        cids.forEach(c -> cidMap.put(c.codigo(), c.nome()));

        Map<String, List<String>> procCboMap = new LinkedHashMap<>();
        for (ProcedimentoCbo r : relCbo) {
            procCboMap.computeIfAbsent(r.procedimentoCodigo(), k -> new java.util.ArrayList<>())
                    .add(r.cboCodigo());
        }
        Map<String, List<String>> procCidMap = new LinkedHashMap<>();
        for (ProcedimentoCid r : relCid) {
            procCidMap.computeIfAbsent(r.procedimentoCodigo(), k -> new java.util.ArrayList<>())
                    .add(r.cidCodigo());
        }

        return new SigtapExportResponse(
                da.dataset.competencia(),
                OffsetDateTime.now(ZoneOffset.UTC),
                procs.size(),
                procs.stream().map(this::toResponse).toList(),
                cboMap, cidMap, procCboMap, procCidMap
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
