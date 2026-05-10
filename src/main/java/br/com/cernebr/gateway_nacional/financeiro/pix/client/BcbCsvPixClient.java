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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

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
    private static final DateTimeFormatter BCB_DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter BCB_DATE_ONLY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String CSV_PATH_TEMPLATE =
            "/content/estabilidadefinanceira/participantes_pix/lista-participantes-instituicoes-em-adesao-pix-{date}.csv";

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
        byte[] body = restClient.get()
                .uri(CSV_PATH_TEMPLATE, date.format(FILENAME_DATE))
                .accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .body(byte[].class);

        if (body == null || body.length == 0) {
            return null;
        }
        // BCB serve com encoding Windows-1252 (Latin-1 estendido) sem
        // declaração consistente. Decodificar explicitamente preserva
        // acentos em "Operação", "São", "Crédito" etc.
        return new String(body, Charset.forName("windows-1252"));
    }

    /**
     * Formato esperado:
     * <pre>
     * ISPB;Nome;Nome Reduzido;Modalidade Participação;Tipo de Participação;Início Operação
     * 00000000;BANCO DO BRASIL S.A.;BCO DO BRASIL;PDCT;DRCT;03/11/2020 09:30
     * </pre>
     * Linhas vazias e header são puladas. Linha mal-formada é ignorada com
     * log em debug — o CSV inteiro do BCB não deve falhar por uma linha
     * corrompida (~1k linhas no total).
     */
    private PixParticipantesResponse parseCsv(String csv, LocalDate dataReferencia) {
        List<PixParticipanteResponse> participantes = new ArrayList<>(1024);
        String[] lines = csv.split("\\r?\\n");
        boolean headerSeen = false;

        for (String line : lines) {
            if (line.isBlank()) continue;
            if (!headerSeen) {
                headerSeen = true;
                continue;
            }
            PixParticipanteResponse parsed = parseLine(line);
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

    private PixParticipanteResponse parseLine(String line) {
        String[] cols = line.split(";", -1);
        if (cols.length < 6) {
            log.debug("BCB CSV line skipped (cols={}): {}", cols.length, line);
            return null;
        }
        try {
            return new PixParticipanteResponse(
                    trim(cols[0]),
                    trim(cols[1]),
                    trim(cols[2]),
                    trim(cols[3]),
                    trim(cols[4]),
                    parseInicioOperacao(trim(cols[5]))
            );
        } catch (RuntimeException ex) {
            log.debug("BCB CSV line failed to parse ({}): {}", ex.getMessage(), line);
            return null;
        }
    }

    private static OffsetDateTime parseInicioOperacao(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            LocalDateTime ldt = LocalDateTime.parse(raw, BCB_DATETIME);
            return ldt.atZone(BR_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            // Algumas linhas legadas vêm sem hora — tenta só a data.
            LocalDate ld = LocalDate.parse(raw, BCB_DATE_ONLY);
            return ld.atStartOfDay(BR_ZONE).toOffsetDateTime();
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
