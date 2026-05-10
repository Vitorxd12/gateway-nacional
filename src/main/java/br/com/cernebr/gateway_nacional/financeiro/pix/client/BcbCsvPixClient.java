package br.com.cernebr.gateway_nacional.financeiro.pix.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.pix.dto.PixParticipanteResponse;
import br.com.cernebr.gateway_nacional.financeiro.pix.dto.PixParticipantesResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provider FALLBACK de PIX participantes — CSV oficial do Banco Central:
 * {@code https://www.bcb.gov.br/content/estabilidadefinanceira/participantes_pix/lista-participantes-instituicoes-em-adesao-pix-YYYYMMDD.csv}.
 *
 * <h2>Inteligência de data retroativa</h2>
 * <p>O BCB publica o CSV apenas em dias úteis bancários — finais de semana
 * e feriados não geram arquivo novo. Quando a request cai num feriado ou
 * fim de semana, o CSV de "hoje" devolve 404. A lógica aqui retrocede dia
 * a dia até encontrar um arquivo, com teto de
 * {@code gateway.pix.bcb.max-fallback-days} (default 7) — cobre o caso
 * pior de Carnaval (5 dias úteis pulados) + fim de semana adjacente.</p>
 *
 * <p>{@link PixParticipantesResponse#dataReferencia()} reflete a data
 * efetiva do arquivo encontrado, não "hoje" — auditoria preservada.</p>
 *
 * <h2>Encoding e parsing</h2>
 * <p>BCB serve o CSV em {@code Windows-1252} (Latin-1 estendido), separador
 * {@code ;}, com cabeçalho. O download é feito como {@code byte[]} para
 * controlar a decodificação manualmente — confiar no Content-Type seria
 * frágil porque o BCB às vezes esquece o {@code charset}.</p>
 *
 * <p>Parsing manual (sem dependência de CSV library): o formato é estável e
 * sem escapes reais nas colunas atuais. Se o BCB introduzir aspas/escapes
 * no futuro, migrar para Apache Commons CSV.</p>
 */
@Slf4j
@Component
public class BcbCsvPixClient implements PixParticipantesClientProvider {

    public static final String PROVIDER_NAME = "BCB-CSV";
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");

    private static final DateTimeFormatter FILENAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String CSV_PATH_TEMPLATE =
            "/content/estabilidadefinanceira/participantes_pix/lista-participantes-instituicoes-em-adesao-pix-{date}.csv";

    /**
     * Headers exigidos do CSV (após normalização: lowercase + sem espaços).
     * Validados via inclusão de conjunto — BCB pode acrescentar colunas, mas
     * estes 4 precisam estar presentes ou rejeitamos com {@code ResourceUnavailableException}.
     * Lista herdada do {@code services/pix/participants.js} da BrasilAPI:
     * note o typo "spi" em {@code tipodeparticipaçãonospi} — é assim mesmo no
     * CSV do BCB (texto truncado pelo header generator do gov.br).
     */
    private static final Set<String> EXPECTED_HEADERS = Set.of(
            "ispb",
            "nomereduzido",
            "modalidadedeparticipaçãonopix",
            "tipodeparticipaçãonospi"
    );

    /**
     * Mapeamento canônico das colunas do CSV BCB (descoberto via parsing real
     * pela BrasilAPI). O CSV tem &gt;9 colunas; usamos apenas estas 4 fixas
     * pelos índices, igual {@code services/pix/participants.js}.
     */
    private static final int COL_NOME = 1;
    private static final int COL_ISPB = 2;
    private static final int COL_TIPO_PARTICIPACAO = 6;
    private static final int COL_MODALIDADE_PARTICIPACAO = 8;
    private static final int MIN_REQUIRED_COLS = 9;

    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)charset=([^;\\s]+)");

    private final RestClient restClient;
    private final int maxFallbackDays;

    public BcbCsvPixClient(RestClient.Builder builder,
                           @Value("${gateway.pix.bcb.base-url:https://www.bcb.gov.br}") String baseUrl,
                           @Value("${gateway.pix.bcb.max-fallback-days:7}") int maxFallbackDays) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.maxFallbackDays = maxFallbackDays;
    }

    @Override
    @CircuitBreaker(name = "pixBcbCsvCB", fallbackMethod = "fallback")
    public PixParticipantesResponse fetchAll() {
        LocalDate today = LocalDate.now(BR_ZONE);
        Throwable lastError = null;

        for (int offset = 0; offset < maxFallbackDays; offset++) {
            LocalDate attempt = today.minusDays(offset);
            try {
                String csv = downloadCsv(attempt);
                if (csv != null && !csv.isBlank()) {
                    log.info("BCB CSV PIX resolved for date={} (offset={} day(s))", attempt, offset);
                    return parseCsv(csv, attempt);
                }
            } catch (HttpClientErrorException.NotFound nf) {
                // 404 é o sinal esperado quando o BCB não publicou naquele dia
                // (fim de semana / feriado). Loga em debug — não polui o WARN.
                log.debug("BCB CSV not published for {} (404). Retrying previous day.", attempt);
                lastError = nf;
            } catch (Exception ex) {
                log.warn("BCB CSV fetch failed for {}: {}", attempt, ex.toString());
                lastError = ex;
            }
        }

        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BCB CSV de PIX indisponível em todos os " + maxFallbackDays + " dias retroativos a partir de " + today + ".",
                lastError);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private PixParticipantesResponse fallback(Throwable cause) {
        log.warn("BCB CSV fallback triggered for PIX participants cause={}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BCB CSV indisponível ou Circuit Breaker aberto.", cause);
    }

    private String downloadCsv(LocalDate date) {
        var entity = restClient.get()
                .uri(CSV_PATH_TEMPLATE, date.format(FILENAME_DATE))
                .accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class);

        byte[] body = entity.getBody();
        if (body == null || body.length == 0) {
            return null;
        }

        // Lê o charset do Content-Type quando declarado (BrasilAPI faz o
        // mesmo); fallback latin1 quando ausente. Latin-1 é suficiente para
        // o conteúdo do CSV do BCB (acentos em "Operação", "São", "Crédito").
        Charset charset = StandardCharsets.ISO_8859_1;
        var contentType = entity.getHeaders().getFirst("Content-Type");
        if (contentType != null) {
            var matcher = CHARSET_PATTERN.matcher(contentType);
            if (matcher.find()) {
                String declared = matcher.group(1).toLowerCase();
                try {
                    charset = "utf-8".equals(declared)
                            ? StandardCharsets.UTF_8
                            : StandardCharsets.ISO_8859_1;
                } catch (Exception ignored) {
                    // mantém o fallback ISO-8859-1
                }
            }
        }
        return new String(body, charset);
    }

    /**
     * Estrutura real do CSV do BCB (descoberto via {@code services/pix/participants.js}
     * da BrasilAPI):
     * <pre>
     * Linha 1: "Lista de participantes ativos do Pix"   ← TÍTULO (descartado)
     * Linha 2: ISPB;Nome;Nome Reduzido;...;Tipo;...;Modalidade;...   ← HEADER (validado)
     * Linha 3+: dados                                                  ← parseados
     * </pre>
     *
     * <p>O header tem mais de 9 colunas — só validamos a presença das 4
     * essenciais ({@link #EXPECTED_HEADERS}) para não quebrar quando o BCB
     * adicionar campos novos. Se um header essencial sumir, lançamos
     * {@code ResourceUnavailableException} ao invés de devolver dados
     * embaralhados — degradação ruidosa &gt; degradação silenciosa.</p>
     *
     * <p>Filtragem de linhas: descartamos linhas em branco e linhas onde
     * a primeira coluna é vazia (espelha o {@code .filter(([ispb]) => ispb)}
     * da BrasilAPI — o nome de variável "ispb" lá é enganoso, na prática
     * filtra a coluna 0).</p>
     */
    private PixParticipantesResponse parseCsv(String csv, LocalDate dataReferencia) {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 3) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BCB CSV de " + dataReferencia + " com menos de 3 linhas — formato inesperado.");
        }

        // Linha 0 = título; linha 1 = header; linhas 2+ = dados.
        validateHeader(lines[1], dataReferencia);

        List<PixParticipanteResponse> participantes = new ArrayList<>(1024);
        for (int i = 2; i < lines.length; i++) {
            PixParticipanteResponse parsed = parseLine(lines[i]);
            if (parsed != null) {
                participantes.add(parsed);
            }
        }

        if (participantes.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BCB CSV de " + dataReferencia + " sem linhas válidas após parsing.");
        }

        return new PixParticipantesResponse(
                participantes.size(),
                dataReferencia,
                PROVIDER_NAME,
                participantes
        );
    }

    private static void validateHeader(String headerLine, LocalDate dataReferencia) {
        String[] rawCols = headerLine.split(";", -1);
        Set<String> normalized = new HashSet<>(rawCols.length);
        for (String col : rawCols) {
            // Espelha {@code header.toLowerCase().replace(/ /g, '')} da BrasilAPI.
            normalized.add(col.toLowerCase().replace(" ", ""));
        }
        if (!normalized.containsAll(EXPECTED_HEADERS)) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BCB CSV de " + dataReferencia + " com header alterado — esperados " +
                            EXPECTED_HEADERS + ", recebidos " + normalized);
        }
    }

    /**
     * Mapeamento canônico (BrasilAPI {@code services/pix/participants.js:76-85}):
     * <ul>
     *   <li>{@code ispb} ← {@code data[2]}</li>
     *   <li>{@code nome} ← {@code data[1]}</li>
     *   <li>{@code nome_reduzido} ← {@code data[1]} (alias intencional do nome — o
     *       BCB não publica essa coluna em separado)</li>
     *   <li>{@code tipo_participacao} ← {@code data[6]}</li>
     *   <li>{@code modalidade_participacao} ← {@code data[8]}</li>
     *   <li>{@code inicio_operacao} ← {@code null} (não consta no CSV)</li>
     * </ul>
     */
    private PixParticipanteResponse parseLine(String line) {
        if (line.isBlank()) return null;
        String[] cols = line.split(";", -1);
        if (cols.length < MIN_REQUIRED_COLS) {
            log.debug("BCB CSV line skipped (cols={} < {}): {}", cols.length, MIN_REQUIRED_COLS, line);
            return null;
        }
        // Filtragem espelhada do .filter(([ispb]) => ispb) — descarta linhas
        // sem conteúdo na primeira coluna.
        if (cols[0].isBlank()) {
            return null;
        }
        try {
            String nome = trim(cols[COL_NOME]);
            return new PixParticipanteResponse(
                    trim(cols[COL_ISPB]),
                    nome,
                    nome,
                    trim(cols[COL_MODALIDADE_PARTICIPACAO]),
                    trim(cols[COL_TIPO_PARTICIPACAO]),
                    null
            );
        } catch (RuntimeException ex) {
            log.debug("BCB CSV line failed to parse ({}): {}", ex.getMessage(), line);
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
