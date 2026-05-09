package br.com.cernebr.gateway_nacional.saude.ans.repository;

import br.com.cernebr.gateway_nacional.saude.ans.dto.OperadoraAnsResponse;
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
 * In-memory repository de operadoras ANS — consolidação dos dumps PDA
 * "operadoras_de_plano_de_saude_ativas" e "...canceladas" da ANS.
 *
 * <h2>Por que não chamada externa</h2>
 * <p>A API CKAN do {@code dados.gov.br} passou a exigir Bearer token
 * (resposta {@code 401 WWW-Authenticate: Bearer} confirmada em prod). Os
 * dumps CSV originais em {@code dadosabertos.ans.gov.br/FTP/PDA/} continuam
 * abertos e atualizados diariamente — o snapshot é convertido para JSON pelo
 * script {@code tmp/build_ans_json.py} e versionado em
 * {@code src/main/resources/data/ans_operadoras.json}.</p>
 *
 * <h2>Por que sem Redis</h2>
 * <p>Mesmo argumento de {@code CfopRepository}/{@code CestRepository}: lookup
 * em {@link HashMap} é {@code <1 µs}; não faz sentido pagar um round-trip TCP
 * em Redis para algo que já está em heap. Memória: ~4k operadoras × ~200
 * caracteres ≈ 800 KB — irrelevante.</p>
 *
 * <h2>Dois índices</h2>
 * <p>Manter o índice por CNPJ e por Registro ANS em memória custa &lt;1 MB e
 * permite que a controller decida em tempo O(1) qual chave foi recebida.</p>
 */
@Slf4j
@Repository
public class AnsRepository {

    private static final String DATA_PATH = "data/ans_operadoras.json";

    private final ObjectMapper objectMapper;
    private Map<String, OperadoraAnsResponse> indexByRegistro = Map.of();
    private Map<String, OperadoraAnsResponse> indexByCnpj = Map.of();

    public AnsRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSnapshot() {
        ClassPathResource resource = new ClassPathResource(DATA_PATH);
        try (InputStream stream = resource.getInputStream()) {
            // Mesmo idiom de CfopRepository/CestRepository — Jackson 3 (tools.jackson)
            // é o ObjectMapper auto-configurado pelo Spring Boot 4.
            List<OperadoraAnsResponse> rows = objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, OperadoraAnsResponse.class));

            Map<String, OperadoraAnsResponse> byRegistro = new HashMap<>(rows.size() * 2);
            Map<String, OperadoraAnsResponse> byCnpj = new HashMap<>(rows.size() * 2);
            for (OperadoraAnsResponse row : rows) {
                if (row.registroAns() != null && !row.registroAns().isBlank()) {
                    byRegistro.put(row.registroAns(), row);
                }
                if (row.cnpj() != null && !row.cnpj().isBlank()) {
                    byCnpj.put(row.cnpj(), row);
                }
            }
            this.indexByRegistro = Map.copyOf(byRegistro);
            this.indexByCnpj = Map.copyOf(byCnpj);
            log.info("ANS registry loaded: {} operadoras indexed (registroAns={}, cnpj={}) from {}",
                    rows.size(), byRegistro.size(), byCnpj.size(), DATA_PATH);
        } catch (IOException ex) {
            // Mesma política de CfopRepository: hard fail no boot. Sem o
            // snapshot a rota nunca poderia responder com sucesso, então
            // tropeçar agora é melhor do que servir 404 silenciosamente
            // em produção.
            throw new IllegalStateException(
                    "Failed to load ANS snapshot from classpath:" + DATA_PATH, ex);
        }
    }

    public Optional<OperadoraAnsResponse> findByRegistroAns(String registroAns) {
        if (registroAns == null || registroAns.isBlank()) return Optional.empty();
        return Optional.ofNullable(indexByRegistro.get(registroAns.trim()));
    }

    public Optional<OperadoraAnsResponse> findByCnpj(String cnpj) {
        if (cnpj == null || cnpj.isBlank()) return Optional.empty();
        return Optional.ofNullable(indexByCnpj.get(cnpj.trim()));
    }

    /** Total de operadoras no snapshot — útil para diagnostics e {@code /actuator/info}. */
    public int size() {
        return indexByRegistro.size();
    }
}
