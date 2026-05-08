package br.com.cernebr.gateway_nacional.cadastral.cnae.client;

import br.com.cernebr.gateway_nacional.cadastral.cnae.dto.CnaeResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fallback CNAE provider — bake-in JSON snapshot of the full IBGE table.
 *
 * <p>Loads {@code data/cnae_subclasses.json} at startup into an in-memory
 * {@link Map} keyed by subclass code (7 digits). Lookup is O(1); the
 * dataset is tiny (~118 KB / 1332 entries) so the memory cost is
 * negligible (~150 KB heap).</p>
 *
 * <p>Why no {@code @CircuitBreaker}: an in-memory map cannot fail
 * transiently. The only way this provider returns nothing is the
 * {@link Optional#empty()} legitimate "code not in the table" case —
 * exactly the same semantics the IBGE primary uses for unknown codes.
 * Wrapping it in CB would only add overhead without observable benefit.</p>
 *
 * <p>Refreshing the snapshot is a manual operation: re-run the IBGE
 * download script and replace {@code src/main/resources/data/cnae_subclasses.json}.
 * The CNAE table is virtually static (changes a handful of times per year
 * via Resoluções CONCLA), so an annual refresh is more than enough.</p>
 */
@Slf4j
@Component
public class LocalCnaeClient implements CnaeClientProvider {

    public static final String PROVIDER_NAME = "Local-CNAE";

    private static final String DATA_PATH = "data/cnae_subclasses.json";

    private final ObjectMapper objectMapper;
    private Map<String, CnaeResponse> indexByCodigo = Map.of();

    public LocalCnaeClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSnapshot() {
        ClassPathResource resource = new ClassPathResource(DATA_PATH);
        try (InputStream stream = resource.getInputStream()) {
            // Same idiom used by `LocalBacenBancoClient` to deserialise a list
            // from a classpath JSON via the shared Jackson 3 ObjectMapper bean.
            List<RawEntry> rows = objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RawEntry.class));

            Map<String, CnaeResponse> index = new HashMap<>(rows.size() * 2);
            for (RawEntry row : rows) {
                if (row.codigo() == null) continue;
                index.put(row.codigo(), new CnaeResponse(row.codigo(), row.descricao()));
            }
            this.indexByCodigo = Map.copyOf(index);
            log.info("Local CNAE registry loaded: {} subclasses indexed from {}", index.size(), DATA_PATH);
        } catch (IOException ex) {
            // Hard fail at boot — without the snapshot, this client cannot
            // function. Surfacing the error early is better than silently
            // serving 503s for every CNAE call when the IBGE is also down.
            throw new IllegalStateException(
                    "Failed to load local CNAE snapshot from classpath:" + DATA_PATH, ex);
        }
    }

    @Override
    public Optional<CnaeResponse> findByCodigo(String codigo) {
        if (codigo == null) return Optional.empty();
        String normalized = codigo.replaceAll("[^0-9]", "");
        if (normalized.isEmpty()) return Optional.empty();
        return Optional.ofNullable(indexByCodigo.get(normalized));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /** Wire-shape of the local snapshot — slim record (codigo, descricao only). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawEntry(String codigo, String descricao) {
    }
}
