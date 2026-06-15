package br.com.cernebr.gateway_nacional.cadastral.cep.service;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory IBGE municipality registry.
 *
 * <p>Loaded once at application startup from a bundled JSON file. Some upstream
 * providers (notably AwesomeAPI legacy responses, BrasilAPI v1) do not include
 * the IBGE municipal code, which is mandatory for downstream NF-e issuance.
 * This service back-fills the missing code by looking up {@code (uf, localidade)}
 * against the registry.</p>
 *
 * <p>Lookup is O(1) via {@link HashMap}. Keys are normalized to uppercase
 * ASCII (NFD-decomposed and diacritic-stripped) so that "São Paulo" and
 * "SAO PAULO" collide on the same entry — providers spell the same city
 * inconsistently and we cannot afford miss rates due to accent drift.</p>
 */
@Slf4j
@Service
public class IbgeEnrichmentService {

    private static final String DATA_FILE = "data/municipios_ibge.json";

    private final ObjectMapper objectMapper;
    private Map<String, MunicipioIbgeEntry> entryByLocation = Map.of();

    public IbgeEnrichmentService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadIbgeRegistry() {
        try (InputStream in = new ClassPathResource(DATA_FILE).getInputStream()) {
            List<MunicipioIbgeEntry> entries = objectMapper.readValue(
                    in,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, MunicipioIbgeEntry.class)
            );

            Map<String, MunicipioIbgeEntry> map = new HashMap<>(entries.size() * 2);
            for (MunicipioIbgeEntry entry : entries) {
                String key = buildKey(entry.uf(), entry.localidade());
                if (key != null && entry.ibge() != null && !entry.ibge().isBlank()) {
                    map.put(key, entry);
                }
            }
            // Defensive immutable snapshot — read path becomes lock-free and tamper-proof.
            this.entryByLocation = Map.copyOf(map);
            log.info("IBGE registry loaded: {} municipalities indexed from {}",
                    this.entryByLocation.size(), DATA_FILE);
        } catch (IOException ex) {
            // Degrade gracefully: gateway keeps serving with potentially null ibge fields
            // rather than failing application bootstrap.
            log.error("Failed to load IBGE registry from classpath '{}'. Continuing with empty registry.",
                    DATA_FILE, ex);
        }
    }

    /**
     * Returns the original response when the IBGE field is already populated
     * or no match is found in the registry. Otherwise, returns a new
     * {@link CepResponse} with the IBGE code filled in.
     */
    public CepResponse enrich(CepResponse response) {
        if (response == null) {
            return null;
        }
        if (response.ibge() != null && !response.ibge().isBlank()) {
            return response;
        }
        String key = buildKey(response.uf(), response.localidade());
        if (key == null) {
            return response;
        }
        MunicipioIbgeEntry entry = entryByLocation.get(key);
        if (entry == null) {
            return response;
        }

        log.debug("IBGE in-memory enrichment hit for uf={} localidade={} -> {}",
                response.uf(), response.localidade(), entry.ibge());

        return response.withIbge(entry.ibge(), entry.siafi(), entry.ddd());
    }

    /**
     * Resolve (UF, nome do município) → código IBGE de 7 dígitos, reusando o
     * mesmo registro em memória e a normalização sem acento/caixa. Devolve
     * {@code null} quando não há match. Usado pela Inteligência de Licitações
     * para preencher o IBGE do órgão comprador — o PNCP entrega apenas o nome
     * do município na unidade, e join por código é mais barato e exato que por nome.
     */
    public String resolveIbge(String uf, String municipio) {
        String key = buildKey(uf, municipio);
        if (key == null) {
            return null;
        }
        MunicipioIbgeEntry entry = entryByLocation.get(key);
        return entry == null ? null : entry.ibge();
    }

    private static String buildKey(String uf, String localidade) {
        if (uf == null || uf.isBlank() || localidade == null || localidade.isBlank()) {
            return null;
        }
        return normalize(uf) + "-" + normalize(localidade);
    }

    private static String normalize(String value) {
        String stripped = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toUpperCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MunicipioIbgeEntry(
            String uf,
            String localidade,
            String ibge,
            Double latitude,
            Double longitude,
            Boolean capital,
            String siafi,
            Integer ddd,
            @JsonProperty("fuso_horario") String fusoHorario
    ) {
    }
}
