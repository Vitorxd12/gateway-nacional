package br.com.cernebr.gateway_nacional.saude.tuss.repository;

import br.com.cernebr.gateway_nacional.saude.tuss.dto.TussCodigoResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshot in-memory do dicionário TUSS da ANS — atua como camada final de
 * resiliência quando a BrasilAPI está indisponível. O arquivo
 * {@code data/tuss_terms.json} é o mesmo snapshot consumido pela BrasilAPI
 * em {@code services/tuss/tussTerms.json}, versionado em git.
 *
 * <p>Custo: ~24k entradas × ~120 caracteres ≈ 3 MB em heap. Lookup
 * O(1) por código + iteração linear para busca por nome. A ANS publica
 * revisões com cadência mensal a trimestral — para refrescar o snapshot,
 * basta sincronizar o JSON e re-buildar o JAR.</p>
 */
@Slf4j
@Repository
public class TussLocalRepository {

    private static final String DATA_PATH = "data/tuss_terms.json";

    private final ObjectMapper objectMapper;
    private List<TussCodigoResponse> all = List.of();
    private Map<String, TussCodigoResponse> byCode = Map.of();

    public TussLocalRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSnapshot() {
        ClassPathResource resource = new ClassPathResource(DATA_PATH);
        try (InputStream stream = resource.getInputStream()) {
            List<RawTuss> raw = objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RawTuss.class));

            List<TussCodigoResponse> normalized = new ArrayList<>(raw.size());
            Map<String, TussCodigoResponse> idx = new HashMap<>(raw.size() * 2);
            for (RawTuss r : raw) {
                String code = sanitize(r.tuss);
                if (code.isEmpty()) continue;
                TussCodigoResponse row = new TussCodigoResponse(code, r.name);
                normalized.add(row);
                idx.put(code, row);
            }
            this.all = List.copyOf(normalized);
            this.byCode = Map.copyOf(idx);
            log.info("TUSS local snapshot loaded: {} códigos indexed from {}", all.size(), DATA_PATH);
        } catch (IOException ex) {
            // Mesma política do AnsRepository — hard fail no boot. Se o
            // snapshot não está disponível, a rota nunca poderia responder.
            throw new IllegalStateException(
                    "Failed to load TUSS snapshot from classpath:" + DATA_PATH, ex);
        }
    }

    public Optional<TussCodigoResponse> findByCode(String code) {
        if (code == null) return Optional.empty();
        return Optional.ofNullable(byCode.get(sanitize(code)));
    }

    public List<TussCodigoResponse> search(String name, String tussPrefix) {
        boolean hasName = name != null && !name.isBlank();
        boolean hasTuss = tussPrefix != null && !tussPrefix.isBlank();
        if (!hasName && !hasTuss) return all;

        String needle = hasName ? normalize(name) : null;
        String prefix = hasTuss ? sanitize(tussPrefix) : null;

        List<TussCodigoResponse> out = new ArrayList<>();
        for (TussCodigoResponse row : all) {
            if (hasName && !normalize(row.nome()).contains(needle)) continue;
            if (hasTuss && !row.tuss().startsWith(prefix)) continue;
            out.add(row);
        }
        return out;
    }

    /**
     * Predicado leve para typeahead — combina três filtros opcionais:
     *
     * <ul>
     *   <li>{@code q} — tokens separados por espaço; cada token é roteado:
     *       dígito-puro → {@code startsWith} contra o código; texto →
     *       {@code contains} (normalizado, sem acento) contra o nome. Todos
     *       os tokens precisam casar (AND).</li>
     *   <li>{@code name} — substring contra o nome.</li>
     *   <li>{@code tussPrefix} — prefixo contra o código.</li>
     * </ul>
     *
     * <p><b>Early-exit:</b> a iteração para assim que {@code limit} matches
     * são coletados. Mantém latência sub-milissegundo mesmo no caso pior
     * (24k entries) — relevante para keystroke-driven typeahead onde N
     * requisições por segundo são esperadas.</p>
     *
     * <p>A ordem retornada é a ordem natural do snapshot (códigos TUSS
     * ascendentes), preservando a mesma estabilidade visual que a BrasilAPI.</p>
     */
    public List<TussCodigoResponse> autocomplete(String q, String name, String tussPrefix, int limit) {
        if (limit <= 0) return List.of();

        String[] tokens = parseTokens(q);
        boolean hasName = name != null && !name.isBlank();
        boolean hasTuss = tussPrefix != null && !tussPrefix.isBlank();

        String needle = hasName ? normalize(name) : null;
        String prefix = hasTuss ? sanitize(tussPrefix) : null;

        List<TussCodigoResponse> out = new ArrayList<>(Math.min(limit, 32));
        for (TussCodigoResponse row : all) {
            if (hasName && !normalize(row.nome()).contains(needle)) continue;
            if (hasTuss && !row.tuss().startsWith(prefix)) continue;
            if (!matchesAllTokens(row, tokens)) continue;

            out.add(row);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static String[] parseTokens(String q) {
        if (q == null || q.isBlank()) return new String[0];
        return q.trim().split("\\s+");
    }

    private static boolean matchesAllTokens(TussCodigoResponse row, String[] tokens) {
        if (tokens.length == 0) return true;
        String normalizedName = null; // lazy
        for (String token : tokens) {
            if (token.isBlank()) continue;
            if (token.matches("\\d+")) {
                if (!row.tuss().startsWith(token)) return false;
            } else {
                if (normalizedName == null) normalizedName = normalize(row.nome());
                if (!normalizedName.contains(normalize(token))) return false;
            }
        }
        return true;
    }

    public int size() {
        return all.size();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D+", "");
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unused")
    private static final class RawTuss {
        public String tuss;
        public String name;
    }
}
