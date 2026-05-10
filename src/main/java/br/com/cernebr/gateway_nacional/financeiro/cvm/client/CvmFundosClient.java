package br.com.cernebr.gateway_nacional.financeiro.cvm.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CvmFundosSnapshot;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.FundoDetailResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente único do dump de fundos CVM:
 * {@code https://dados.cvm.gov.br/dados/FI/CAD/DADOS/cad_fi.csv}.
 *
 * <p>Diferente do {@link CvmCorretorasClient}, este CSV é <b>header-driven</b>
 * — o parser lê a 1ª linha pra mapear nomes de coluna ({@code TP_FUNDO},
 * {@code CNPJ_FUNDO}, etc.) para snake_case bonitinho ({@code tipo_fundo},
 * {@code cnpj}). Mais robusto: se a CVM trocar a ordem das colunas, o parser
 * continua funcionando; se a CVM remover uma coluna, o campo correspondente
 * vira {@code null}.</p>
 *
 * <p>O dump tem ~30k fundos e ~10MB descomprimido em latin1.</p>
 */
@Slf4j
@Component
public class CvmFundosClient {

    public static final String PROVIDER_NAME = "CVM-Fundos";
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");

    private static final String DEFAULT_URL =
            "https://dados.cvm.gov.br/dados/FI/CAD/DADOS/cad_fi.csv";
    private static final String DIGITS_ONLY = "\\D";

    /**
     * Mapeamento {@code header CSV CVM → atributo do DTO}. Sincronizado com
     * o {@code changeHeader} do {@code services/cvm/fundos.js} da BrasilAPI.
     * Headers ausentes deste mapa são silenciosamente ignorados — permite que
     * a CVM acrescente colunas sem quebrar o cliente.
     */
    private static final Map<String, String> HEADER_TRANSFORM = Map.ofEntries(
            Map.entry("TP_FUNDO", "tipo_fundo"),
            Map.entry("CNPJ_FUNDO", "cnpj"),
            Map.entry("DENOM_SOCIAL", "denominacao_social"),
            Map.entry("DT_REG", "data_registro"),
            Map.entry("DT_CONST", "data_constituicao"),
            Map.entry("CD_CVM", "codigo_cvm"),
            Map.entry("DT_CANCEL", "data_cancelamento"),
            Map.entry("SIT", "situacao"),
            Map.entry("DT_INI_SIT", "data_inicio_situacao"),
            Map.entry("DT_INI_ATIV", "data_inicio_atividade"),
            Map.entry("DT_INI_EXERC", "data_inicio_exercicio"),
            Map.entry("DT_FIM_EXERC", "data_fim_exercicio"),
            Map.entry("CLASSE", "classe"),
            Map.entry("DT_INI_CLASSE", "data_inicio_classe"),
            Map.entry("RENTAB_FUNDO", "rentabilidade"),
            Map.entry("CONDOM", "condominio"),
            Map.entry("FUNDO_COTAS", "cotas"),
            Map.entry("FUNDO_EXCLUSIVO", "fundo_exclusivo"),
            Map.entry("TRIB_LPRAZO", "tributacao_longo_prazo"),
            Map.entry("PUBLICO_ALVO", "publico_alvo"),
            Map.entry("ENTID_INVEST", "entidade_investimento"),
            Map.entry("TAXA_PERFM", "taxa_performance"),
            Map.entry("INF_TAXA_PERFM", "informacao_taxa_performance"),
            Map.entry("TAXA_ADM", "taxa_administracao"),
            Map.entry("INF_TAXA_ADM", "informacao_taxa_administracao"),
            Map.entry("VL_PATRIM_LIQ", "valor_patrimonio_liquido"),
            Map.entry("DT_PATRIM_LIQ", "data_patrimonio_liquido"),
            Map.entry("DIRETOR", "diretor"),
            Map.entry("CNPJ_ADMIN", "cnpj_administrador"),
            Map.entry("ADMIN", "administrador"),
            Map.entry("PF_PJ_GESTOR", "tipo_pessoa_gestor"),
            Map.entry("CPF_CNPJ_GESTOR", "cpf_cnpj_gestor"),
            Map.entry("GESTOR", "gestor"),
            Map.entry("CNPJ_AUDITOR", "cnpj_auditor"),
            Map.entry("AUDITOR", "auditor"),
            Map.entry("CNPJ_CUSTODIANTE", "cnpj_custodiante"),
            Map.entry("CUSTODIANTE", "custodiante"),
            Map.entry("CNPJ_CONTROLADOR", "cnpj_controlador"),
            Map.entry("CONTROLADOR", "controlador"),
            Map.entry("INVEST_CEMPR_EXTER", "investimento_externo"),
            Map.entry("CLASSE_ANBIMA", "classe_anbima")
    );

    private final RestClient restClient;
    private final String csvUrl;

    public CvmFundosClient(RestClient.Builder builder,
                           @Value("${gateway.cvm.fundos.url:" + DEFAULT_URL + "}") String csvUrl) {
        this.restClient = builder.build();
        this.csvUrl = csvUrl;
    }

    @CircuitBreaker(name = "cvmFundosCB", fallbackMethod = "fallback")
    public CvmFundosSnapshot fetchSnapshot() {
        log.debug("CVM fundos snapshot fetch — baixando CSV de {}", csvUrl);
        byte[] csvBytes = restClient.get()
                .uri(csvUrl)
                .accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .body(byte[].class);

        if (csvBytes == null || csvBytes.length == 0) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CVM fundos: CSV vazio ou ausente em " + csvUrl);
        }

        String csv = new String(csvBytes, StandardCharsets.ISO_8859_1);
        List<FundoDetailResponse> fundos = parseCsv(csv);

        if (fundos.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CVM fundos: nenhum fundo parseado.");
        }

        log.info("CVM fundos snapshot atualizado: {} fundos parseados", fundos.size());
        return new CvmFundosSnapshot(fundos, LocalDate.now(BR_ZONE));
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CvmFundosSnapshot fallback(Throwable cause) {
        log.warn("CVM fundos fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "CVM fundos indisponível ou Circuit Breaker aberto.", cause);
    }

    private List<FundoDetailResponse> parseCsv(String csv) {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CVM fundos: CSV com menos de 2 linhas, formato inesperado.");
        }

        Map<String, Integer> columnIndex = buildColumnIndex(lines[0]);

        List<FundoDetailResponse> fundos = new ArrayList<>(32_000);
        for (int i = 1; i < lines.length; i++) {
            FundoDetailResponse parsed = parseLine(lines[i], columnIndex);
            if (parsed != null) fundos.add(parsed);
        }
        return fundos;
    }

    /**
     * Lê a linha de cabeçalho e produz {@code transformedHeader → indexNaLinha}.
     * Headers desconhecidos do {@link #HEADER_TRANSFORM} são ignorados.
     */
    private Map<String, Integer> buildColumnIndex(String headerLine) {
        String[] rawHeaders = headerLine.split(";", -1);
        Map<String, Integer> indexByTransformed = new HashMap<>(rawHeaders.length);
        for (int i = 0; i < rawHeaders.length; i++) {
            String transformed = HEADER_TRANSFORM.get(rawHeaders[i].trim());
            if (transformed != null) {
                indexByTransformed.put(transformed, i);
            }
        }
        if (!indexByTransformed.containsKey("cnpj")) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CVM fundos: header sem coluna CNPJ_FUNDO — formato alterado.");
        }
        return indexByTransformed;
    }

    private FundoDetailResponse parseLine(String line, Map<String, Integer> idx) {
        if (line.isBlank()) return null;
        String[] cols = line.split(";", -1);
        Integer cnpjIdx = idx.get("cnpj");
        if (cnpjIdx == null || cnpjIdx >= cols.length || cols[cnpjIdx].isBlank()) {
            return null;
        }

        return new FundoDetailResponse(
                cleanCnpj(cols, idx, "cnpj"),
                col(cols, idx, "denominacao_social"),
                col(cols, idx, "tipo_fundo"),
                col(cols, idx, "codigo_cvm"),
                col(cols, idx, "situacao"),
                col(cols, idx, "data_registro"),
                col(cols, idx, "data_constituicao"),
                col(cols, idx, "data_cancelamento"),
                col(cols, idx, "data_inicio_situacao"),
                col(cols, idx, "data_inicio_atividade"),
                col(cols, idx, "data_inicio_exercicio"),
                col(cols, idx, "data_fim_exercicio"),
                col(cols, idx, "classe"),
                col(cols, idx, "data_inicio_classe"),
                col(cols, idx, "rentabilidade"),
                col(cols, idx, "condominio"),
                col(cols, idx, "cotas"),
                col(cols, idx, "fundo_exclusivo"),
                col(cols, idx, "tributacao_longo_prazo"),
                col(cols, idx, "publico_alvo"),
                col(cols, idx, "entidade_investimento"),
                col(cols, idx, "taxa_performance"),
                col(cols, idx, "informacao_taxa_performance"),
                col(cols, idx, "taxa_administracao"),
                col(cols, idx, "informacao_taxa_administracao"),
                col(cols, idx, "valor_patrimonio_liquido"),
                col(cols, idx, "data_patrimonio_liquido"),
                col(cols, idx, "diretor"),
                col(cols, idx, "cnpj_administrador"),
                col(cols, idx, "administrador"),
                col(cols, idx, "tipo_pessoa_gestor"),
                col(cols, idx, "cpf_cnpj_gestor"),
                col(cols, idx, "gestor"),
                col(cols, idx, "cnpj_auditor"),
                col(cols, idx, "auditor"),
                col(cols, idx, "cnpj_custodiante"),
                col(cols, idx, "custodiante"),
                col(cols, idx, "cnpj_controlador"),
                col(cols, idx, "controlador"),
                col(cols, idx, "investimento_externo"),
                col(cols, idx, "classe_anbima")
        );
    }

    private static String col(String[] cols, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        if (i == null || i >= cols.length) return null;
        String v = cols[i];
        if (v == null) return null;
        String trimmed = v.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String cleanCnpj(String[] cols, Map<String, Integer> idx, String name) {
        String raw = col(cols, idx, name);
        return raw == null ? null : raw.replaceAll(DIGITS_ONLY, "");
    }
}
