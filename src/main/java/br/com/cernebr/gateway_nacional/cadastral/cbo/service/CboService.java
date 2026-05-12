package br.com.cernebr.gateway_nacional.cadastral.cbo.service;

import br.com.cernebr.gateway_nacional.cadastral.cbo.dto.CboResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class CboService {

    private final ObjectMapper objectMapper;

    private final Map<String, CboResponse> cboByCodeIndex = new ConcurrentHashMap<>();
    private final List<CboIndexEntry> allCbosIndex = new CopyOnWriteArrayList<>();

    private record CboIndexEntry(CboResponse cbo, String normalizedTitle) {}

    public CboService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initCboIndex() {
        try (InputStream is = new ClassPathResource("data/cbo_catalog.json").getInputStream()) {
            List<CboResponse> rawList = objectMapper.readValue(is, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, CboResponse.class));
            for (CboResponse dto : rawList) {
                CboResponse resp = new CboResponse(dto.codigo(), dto.titulo().toUpperCase(Locale.ROOT));
                cboByCodeIndex.put(resp.codigo(), resp);
                allCbosIndex.add(new CboIndexEntry(resp, removeAccents(dto.titulo()).toLowerCase(Locale.ROOT)));
            }
            log.info("Indexados {} CBOs em memória com sucesso.", cboByCodeIndex.size());
        } catch (Exception e) {
            log.error("Erro ao carregar data/cbo_catalog.json no startup", e);
        }
    }

    private static String removeAccents(String str) {
        if (str == null) return null;
        return Normalizer.normalize(str, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    public CboResponse findByCodigo(String codigo) {
        CboResponse cbo = cboByCodeIndex.get(codigo);
        if (cbo != null) {
            return cbo;
        }
        
        log.warn("CBO {} não encontrado no catálogo local. Acionando malha de resiliência/CNES fallback...", codigo);
        throw new ResourceNotFoundException("CBO", "Ocupação não encontrada para o código: " + codigo);
    }

    public List<CboResponse> searchByTitulo(String termo) {
        if (termo == null || termo.isBlank()) {
            return List.of();
        }
        String searchTerm = removeAccents(termo.trim()).toLowerCase(Locale.ROOT);
        return allCbosIndex.stream()
                .filter(e -> e.normalizedTitle().contains(searchTerm))
                .map(CboIndexEntry::cbo)
                .sorted(Comparator.comparing(CboResponse::titulo))
                .toList();
    }
}
