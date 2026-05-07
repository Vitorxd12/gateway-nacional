package br.com.cernebr.gateway_nacional.saude.service;

import br.com.cernebr.gateway_nacional.saude.dto.AuditoriaInadimplenciaResponse;
import br.com.cernebr.gateway_nacional.saude.dto.EquipeEGestorResponse;
import br.com.cernebr.gateway_nacional.saude.dto.ProducaoSisabResponse;
import br.com.cernebr.gateway_nacional.saude.dto.ProfissionalCnesResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The "auditor automático" — the cross-domain orchestrator that turns three
 * independent gov.br integrations into a single actionable verdict: <i>which
 * professional caused the município to lose a federal repasse?</i>
 *
 * <h2>Pipeline</h2>
 * Three downstreams run concurrently on virtual threads — each is independently
 * cached in Redis (15 days TTL inside the {@code "saude"} cache namespace),
 * so a warm system answers from cache for cents of latency.
 * <ol>
 *   <li><b>e-Gestor</b> → equipes APS reportadas com {@code statusSuspensao}
 *       e motivo. The auditor filters those whose motive contains the
 *       production keywords ({@code "producao"}, {@code "envio"}, accent-
 *       insensitive) — the financial signature of a SISAB validation gap;</li>
 *   <li><b>CNES</b> → profissionais cadastrados no estabelecimento. Indexed
 *       by canonical INE (numeric, leading-zeros stripped) so cross-source
 *       matching survives format quirks of each gov.br portal;</li>
 *   <li><b>SISAB</b> → validações de produção. Reduced to a {@code Set} of
 *       {@code "approved INEs"} for the competência — absence in this set
 *       is the unambiguous signal of inadimplência.</li>
 * </ol>
 *
 * <p>For every suspended team that matches all three criteria (suspended
 * by production, present in the requested CNES, INE not in approved SISAB
 * set), the auditor emits a verdict with the full list of professionals
 * working there — the auditor's actionable list.</p>
 */
@Slf4j
@Service
public class AuditoriaSaudeService {

    private static final String DOMAIN = "saude";
    private static final String AUDITOR = "auditor";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    private static final String STATUS_SUSPENSO = "SUSPENSO";
    private static final String STATUS_NAO_SUSPENSO = "NÃO SUSPENSO";
    private static final String SISAB_APROVADO = "APROVADO";

    private final EGestorService eGestorService;
    private final CnesService cnesService;
    private final SisabService sisabService;
    private final MeterRegistry meterRegistry;

    public AuditoriaSaudeService(EGestorService eGestorService,
                                 CnesService cnesService,
                                 SisabService sisabService,
                                 MeterRegistry meterRegistry) {
        this.eGestorService = eGestorService;
        this.cnesService = cnesService;
        this.sisabService = sisabService;
        this.meterRegistry = meterRegistry;
    }

    public List<AuditoriaInadimplenciaResponse> auditarEquipes(String ibge, String cnes, String competencia) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<AuditoriaInadimplenciaResponse> result = doAuditar(ibge, cnes, competencia);
            recordOutcome("success", sample);
            log.info("Auditoria computed {} verdicts for IBGE={} CNES={} competencia={}",
                    result.size(), ibge, cnes, competencia);
            return result;
        } catch (RuntimeException ex) {
            recordOutcome("failure", sample);
            log.warn("Auditoria failed for IBGE={} CNES={} competencia={}: {}",
                    ibge, cnes, competencia, ex.getMessage());
            throw ex;
        }
    }

    private List<AuditoriaInadimplenciaResponse> doAuditar(String ibge, String cnes, String competencia) {
        List<EquipeEGestorResponse> equipes;
        List<ProfissionalCnesResponse> profissionais;
        List<ProducaoSisabResponse> producao;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<EquipeEGestorResponse>> fEquipes =
                    CompletableFuture.supplyAsync(() -> eGestorService.findEquipes(ibge, competencia), executor);
            CompletableFuture<List<ProfissionalCnesResponse>> fProfs =
                    CompletableFuture.supplyAsync(() -> cnesService.findProfissionais(cnes, ibge), executor);
            CompletableFuture<List<ProducaoSisabResponse>> fProducao =
                    CompletableFuture.supplyAsync(() -> sisabService.findProducao(ibge, competencia), executor);

            equipes = joinUnwrapped(fEquipes);
            profissionais = joinUnwrapped(fProfs);
            producao = joinUnwrapped(fProducao);
        }

        // SISAB → set of approved INEs (canonicalized) for the requested CNES.
        Set<String> approvedInesInCnes = producao.stream()
                .filter(row -> row.cnes() != null && cnes.equals(row.cnes().trim()))
                .filter(row -> SISAB_APROVADO.equalsIgnoreCase(safe(row.statusValidacao())))
                .map(row -> canonicalIne(row.ine()))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));

        // CNES → profissionais grouped by canonical INE. Only INEs that
        // exist in the requested establishment survive — the auditor scope.
        Map<String, List<ProfissionalCnesResponse>> profsByIne = profissionais.stream()
                .collect(Collectors.groupingBy(p -> canonicalIne(p.ineEquipe())));

        // e-Gestor → suspended teams whose motive points at production/envio.
        // Each surviving team becomes a verdict — provided the team's INE
        // appears in the requested CNES (otherwise the team belongs to a
        // different establishment and is out of scope).
        List<AuditoriaInadimplenciaResponse> verdicts = new ArrayList<>();
        for (EquipeEGestorResponse equipe : equipes) {
            String motivo = safe(equipe.statusSuspensao());
            if (!isSuspendedByProduction(motivo)) continue;

            String ineCanonical = canonicalIne(equipe.ine());
            List<ProfissionalCnesResponse> profsDaEquipe = profsByIne.getOrDefault(ineCanonical, List.of());
            if (profsDaEquipe.isEmpty()) continue;

            List<String> inadimplentes = approvedInesInCnes.contains(ineCanonical)
                    ? List.of()
                    : profsDaEquipe.stream()
                            .map(AuditoriaSaudeService::displayName)
                            .filter(s -> !s.isEmpty())
                            .toList();

            verdicts.add(new AuditoriaInadimplenciaResponse(
                    ineCanonical,
                    STATUS_SUSPENSO,
                    motivo,
                    cnes,
                    inadimplentes
            ));
        }
        return verdicts;
    }

    /**
     * Production-related when the motive contains {@code "producao"} or
     * {@code "envio"} after diacritic stripping — covers e-Gestor variants
     * like "PRODUÇÃO INSUFICIENTE", "ENVIO NÃO REALIZADO", "SEM ENVIO DE
     * PRODUCAO APROVADA".
     */
    private static boolean isSuspendedByProduction(String motivo) {
        if (motivo == null || STATUS_NAO_SUSPENSO.equalsIgnoreCase(motivo)) return false;
        String norm = Normalizer.normalize(motivo, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        return norm.contains("producao") || norm.contains("envio");
    }

    /**
     * Canonical INE for cross-source matching: strip everything that is not
     * a digit, then drop leading zeros. Empty string for null/blank input.
     */
    private static String canonicalIne(String ine) {
        if (ine == null) return "";
        String digits = ine.replaceAll("[^0-9]", "");
        int firstNonZero = 0;
        while (firstNonZero < digits.length() - 1 && digits.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        return digits.isEmpty() ? "" : digits.substring(firstNonZero);
    }

    private static String displayName(ProfissionalCnesResponse prof) {
        if (prof.nome() != null && !prof.nome().isBlank()) return prof.nome().trim();
        if (prof.cns() != null && !prof.cns().isBlank()) return "CNS " + prof.cns().trim();
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Unwraps {@link CompletionException} so the original
     * {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
     * bubbles up cleanly to the global handler — no double-wrapped exception
     * traces in the 503 response.
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
                .tag("provider", AUDITOR)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", AUDITOR,
                "outcome", outcome).increment();
    }
}
