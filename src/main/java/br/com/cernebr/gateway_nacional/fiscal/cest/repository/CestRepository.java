package br.com.cernebr.gateway_nacional.fiscal.cest.repository;

import br.com.cernebr.gateway_nacional.fiscal.cest.dto.CestResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory repository of CEST (Código Especificador da Substituição
 * Tributária) entries loaded from a bake-in JSON snapshot.
 *
 * <h2>Why no upstream call</h2>
 * <p>The CEST table is published by CONFAZ via Convênio ICMS 142/2018
 * and changes only when a new convênio amends the annex — typically
 * once or twice a year. There is no public REST API maintained by the
 * Receita Federal that returns an individual CEST entry; integrators
 * scrape the PDF that CONFAZ publishes. Bake-in is the canonical
 * strategy: zero network, zero failure modes, version-controlled in
 * git so every fiscal-table change is auditable.</p>
 *
 * <h2>Why two indices</h2>
 * <p>An ERP issuing an NF-e already knows the product's NCM and asks
 * "which CEST(s) apply?". That is the dominant query and runs against
 * {@link #indexByNcm} — a {@code Map<String, List<CestResponse>>}
 * because cardinality is N:N (a single NCM frequently maps to multiple
 * CESTs across different segments). The reverse direction
 * (CEST → entry) is also useful for validation flows, served by
 * {@link #indexByCest}; here we do <em>not</em> deduplicate by code
 * because the same CEST is repeated in the snapshot for each NCM it
 * covers — we keep the first occurrence as the canonical descriptor.</p>
 *
 * <p>Memory cost is trivial: ~1k entries × ~300 chars ≈ 300 KB of heap.
 * Refresh = edit {@code src/main/resources/data/cest.json} and rebuild.</p>
 */
@Slf4j
@Repository
public class CestRepository {

    private static final String DATA_PATH = "data/cest.json";

    private final ObjectMapper objectMapper;
    private Map<String, CestResponse> indexByCest = Map.of();
    private Map<String, List<CestResponse>> indexByNcm = Map.of();

    public CestRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSnapshot() {
        ClassPathResource resource = new ClassPathResource(DATA_PATH);
        try (InputStream stream = resource.getInputStream()) {
            // Same idiom used by CfopRepository — the shared Jackson 3
            // ObjectMapper bean keeps configuration uniform across the app.
            List<CestResponse> rows = objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CestResponse.class));

            Map<String, CestResponse> byCest = new HashMap<>(rows.size() * 2);
            Map<String, List<CestResponse>> byNcm = new HashMap<>(rows.size());
            for (CestResponse row : rows) {
                if (row.cest() == null || row.cest().isBlank()) continue;
                if (row.ncm() == null || row.ncm().isBlank()) continue;

                // First occurrence wins — same CEST repeated for each NCM
                // it covers; the descriptor itself is identical, so order
                // doesn't matter for the canonical entry.
                byCest.putIfAbsent(row.cest(), row);
                byNcm.computeIfAbsent(row.ncm(), k -> new ArrayList<>()).add(row);
            }

            // Freeze the inner lists to keep the structure immutable end-to-end.
            Map<String, List<CestResponse>> byNcmFrozen = new HashMap<>(byNcm.size() * 2);
            for (Map.Entry<String, List<CestResponse>> e : byNcm.entrySet()) {
                byNcmFrozen.put(e.getKey(), List.copyOf(e.getValue()));
            }

            this.indexByCest = Map.copyOf(byCest);
            this.indexByNcm = Map.copyOf(byNcmFrozen);
            log.info("CEST registry loaded: {} unique CESTs across {} NCMs from {}",
                    byCest.size(), byNcmFrozen.size(), DATA_PATH);
        } catch (IOException ex) {
            // Hard fail at boot — without the snapshot the controller would
            // serve 503/404 for every request. Failing early surfaces a
            // misconfigured deploy before traffic reaches the route.
            throw new IllegalStateException(
                    "Failed to load CEST snapshot from classpath:" + DATA_PATH, ex);
        }
    }

    public Optional<CestResponse> findByCest(String cest) {
        if (cest == null || cest.isBlank()) return Optional.empty();
        return Optional.ofNullable(indexByCest.get(cest.trim()));
    }

    public List<CestResponse> findByNcm(String ncm) {
        if (ncm == null || ncm.isBlank()) return List.of();
        return indexByNcm.getOrDefault(ncm.trim(), Collections.emptyList());
    }

    /** Total de CESTs únicos carregados — usado para diagnostics. */
    public int size() {
        return indexByCest.size();
    }
}
