package br.com.cernebr.gateway_nacional.fiscal.cfop.repository;

import br.com.cernebr.gateway_nacional.fiscal.cfop.dto.CfopResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory repository of CFOP (Código Fiscal de Operações e Prestações)
 * codes loaded from a bake-in JSON snapshot.
 *
 * <h2>Why no upstream call</h2>
 * <p>Unlike NCM/CNAE, the CFOP table is essentially frozen by Convênio
 * SINIEF — the latest meaningful additions date back two decades. There
 * is no public REST API maintained by the Receita Federal that returns
 * an individual CFOP entry; the table just lives in tax law. Bake-in
 * is therefore the canonical strategy: zero network, zero failure modes,
 * zero observability surface to maintain.</p>
 *
 * <h2>Why no Redis cache</h2>
 * <p>Lookup runs in {@code <1 µs} on a {@link HashMap} hit; pushing this
 * through Redis would add a network hop just to retrieve data we already
 * hold in process. Memory cost is trivial: ~600 entries × ~500 chars
 * ≈ 300 KB of heap, dwarfed by any HTTP client buffer.</p>
 *
 * <p>Refreshing the snapshot is a one-line operation: edit
 * {@code src/main/resources/data/cfop.json} and rebuild. Versioning the
 * dataset alongside the code is feature, not bug — every change to the
 * fiscal table is auditable in git history.</p>
 */
@Slf4j
@Repository
public class CfopRepository {

    private static final String DATA_PATH = "data/cfop.json";

    private final ObjectMapper objectMapper;
    private Map<String, CfopResponse> indexByCodigo = Map.of();

    public CfopRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSnapshot() {
        ClassPathResource resource = new ClassPathResource(DATA_PATH);
        try (InputStream stream = resource.getInputStream()) {
            // Same idiom used by LocalCnaeClient/LocalBacenBancoClient — the
            // shared Jackson 3 ObjectMapper bean keeps configuration uniform.
            List<CfopResponse> rows = objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CfopResponse.class));

            Map<String, CfopResponse> index = new HashMap<>(rows.size() * 2);
            for (CfopResponse row : rows) {
                if (row.codigo() == null) continue;
                index.put(row.codigo(), row);
            }
            this.indexByCodigo = Map.copyOf(index);
            log.info("CFOP registry loaded: {} codes indexed from {}", index.size(), DATA_PATH);
        } catch (IOException ex) {
            // Hard fail at boot — without the snapshot the controller would
            // serve 503/404 for every request. Failing early surfaces a
            // misconfigured deploy before traffic reaches the route.
            throw new IllegalStateException(
                    "Failed to load CFOP snapshot from classpath:" + DATA_PATH, ex);
        }
    }

    public Optional<CfopResponse> findByCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) return Optional.empty();
        return Optional.ofNullable(indexByCodigo.get(codigo.trim()));
    }

    /** Total de CFOPs carregados — usado para o {@code /actuator/info} ou diagnostics. */
    public int size() {
        return indexByCodigo.size();
    }
}
