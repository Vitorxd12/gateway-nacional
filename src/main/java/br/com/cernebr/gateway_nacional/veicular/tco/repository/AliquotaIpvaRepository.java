package br.com.cernebr.gateway_nacional.veicular.tco.repository;

import br.com.cernebr.gateway_nacional.veicular.tco.dto.AliquotaUfEntry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Motor de Alíquotas em Memória — repositório canônico das alíquotas oficiais
 * de IPVA por UF (veículos de passeio) e da estimativa média da Taxa de
 * Transferência de Propriedade do Detran local.
 *
 * <h2>Por que bake-in (sem chamada upstream)</h2>
 * <p>Não existe uma API REST federal unificada que devolva a alíquota de
 * IPVA de uma UF — cada Secretaria da Fazenda estadual publica a sua em lei
 * própria, e os valores só viram <em>uma vez ao ano</em> (lei orçamentária
 * estadual). Carregar um snapshot estático em memória é a estratégia
 * canônica: zero rede, zero modo-de-falha, zero superfície de observabilidade
 * a manter. Atualizar é uma operação de uma linha — editar
 * {@code src/main/resources/data/ipva_aliquotas_uf.json} e rebuildar; cada
 * alteração da malha fiscal fica auditável no git.</p>
 *
 * <h2>Malha de Fallback</h2>
 * <p>Se a busca referenciar uma UF não mapeada na base principal, o
 * repositório devolve {@link #fallbackEntry(String)} — alíquota modal
 * nacional de <b>3%</b> e taxa de transferência mediana — e o service
 * sinaliza a aproximação no DTO via {@code fallbackAplicado=true}.</p>
 */
@Slf4j
@Repository
public class AliquotaIpvaRepository {

    private static final String DATA_PATH = "data/ipva_aliquotas_uf.json";

    /**
     * Alíquota modal nacional aplicada quando a UF não está mapeada — a
     * maioria dos estados pratica 3% para veículos de passeio, então essa é
     * a aproximação menos enviesada possível na ausência de dado canônico.
     */
    public static final BigDecimal ALIQUOTA_MODAL_NACIONAL = new BigDecimal("0.03");

    /**
     * Taxa de transferência mediana usada no fallback — ordem de grandeza
     * compatível com a faixa praticada pelos Detrans mapeados.
     */
    private static final BigDecimal TAXA_TRANSFERENCIA_FALLBACK = new BigDecimal("180.00");

    private final ObjectMapper objectMapper;
    private Map<String, AliquotaUfEntry> indexByUf = Map.of();

    public AliquotaIpvaRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSnapshot() {
        ClassPathResource resource = new ClassPathResource(DATA_PATH);
        try (InputStream stream = resource.getInputStream()) {
            List<AliquotaUfEntry> rows = objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AliquotaUfEntry.class));

            Map<String, AliquotaUfEntry> index = new HashMap<>(rows.size() * 2);
            for (AliquotaUfEntry row : rows) {
                if (row.uf() == null || row.aliquotaIpva() == null) continue;
                index.put(row.uf().trim().toUpperCase(Locale.ROOT), row);
            }
            this.indexByUf = Map.copyOf(index);
            log.info("IPVA registry loaded: {} UFs indexadas de {}", index.size(), DATA_PATH);
        } catch (IOException ex) {
            // Hard fail no boot — sem o snapshot toda consulta de TCO viraria
            // fallback, mascarando um deploy mal configurado.
            throw new IllegalStateException(
                    "Falha ao carregar snapshot de alíquotas IPVA do classpath:" + DATA_PATH, ex);
        }
    }

    /**
     * Resolve a entrada canônica da UF. Retorna {@code null} quando a UF não
     * está mapeada — o caller decide aplicar o {@link #fallbackEntry(String)}.
     */
    public AliquotaUfEntry findByUf(String uf) {
        if (uf == null || uf.isBlank()) return null;
        return indexByUf.get(uf.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Entrada de fallback resiliente: alíquota modal nacional (3%) e taxa de
     * transferência mediana, para UFs fora da base canônica.
     */
    public AliquotaUfEntry fallbackEntry(String uf) {
        String normalizada = uf == null ? "??" : uf.trim().toUpperCase(Locale.ROOT);
        return new AliquotaUfEntry(normalizada, ALIQUOTA_MODAL_NACIONAL, TAXA_TRANSFERENCIA_FALLBACK);
    }

    /** Total de UFs canônicas carregadas — útil para {@code /actuator/info} ou diagnostics. */
    public int size() {
        return indexByUf.size();
    }
}
