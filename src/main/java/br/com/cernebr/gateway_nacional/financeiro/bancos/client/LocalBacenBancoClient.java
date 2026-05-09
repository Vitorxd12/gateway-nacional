package br.com.cernebr.gateway_nacional.financeiro.bancos.client;

import br.com.cernebr.gateway_nacional.financeiro.bancos.dto.BancoResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Secondary bank-catalogue provider — bundled in-memory BACEN dump.
 *
 * <p>Loaded once at startup from {@code data/bancos_bacen.json} into an
 * immutable {@link Map} keyed by COMPE code. Lookups are O(1) and offline,
 * making this provider the zero-latency safety net for the cascade. The
 * dump is intentionally small (curated subset of high-traffic institutions);
 * production deployments may overlay a complete BACEN dump by replacing
 * the JSON resource at build or boot time.</p>
 */
@Slf4j
@Component
public class LocalBacenBancoClient implements BancoClientProvider {

    public static final String PROVIDER_NAME = "LocalBacen";

    private static final String DATA_FILE = "data/bancos_bacen.json";

    private final ObjectMapper objectMapper;
    private Map<String, BancoResponse> bancosByCodigo = Map.of();

    public LocalBacenBancoClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadBancosRegistry() {
        try (InputStream in = new ClassPathResource(DATA_FILE).getInputStream()) {
            List<LocalBancoEntry> entries = objectMapper.readValue(
                    in,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LocalBancoEntry.class)
            );

            // LinkedHashMap preserves the JSON order — useful for fetchAll() which
            // returns the values directly without a separate sort step.
            Map<String, BancoResponse> map = new LinkedHashMap<>(entries.size() * 2);
            for (LocalBancoEntry entry : entries) {
                if (entry.codigo() == null || entry.codigo().isBlank()) {
                    continue;
                }
                map.put(entry.codigo(), entry.toBancoResponse());
            }
            this.bancosByCodigo = Map.copyOf(map);
            log.info("Local BACEN registry loaded: {} bancos indexed from {}",
                    this.bancosByCodigo.size(), DATA_FILE);
        } catch (IOException ex) {
            log.error("Failed to load local BACEN registry from classpath '{}'. Continuing with empty registry.",
                    DATA_FILE, ex);
        }
    }

    @Override
    public List<BancoResponse> fetchAll() {
        if (bancosByCodigo.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Registro local de bancos não foi carregado.");
        }
        return List.copyOf(bancosByCodigo.values());
    }

    @Override
    public BancoResponse fetchByCodigo(String codigo) {
        BancoResponse banco = bancosByCodigo.get(codigo);
        if (banco == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Código de banco não encontrado no registro local: " + codigo);
        }
        return banco;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LocalBancoEntry(String ispb, String nome, String codigo, String nomeCompleto) {
        BancoResponse toBancoResponse() {
            return new BancoResponse(ispb, nome, codigo, nomeCompleto);
        }
    }
}
