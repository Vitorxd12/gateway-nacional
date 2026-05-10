package br.com.cernebr.gateway_nacional.cadastral.ddd.client;

import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddSnapshot;
import br.com.cernebr.gateway_nacional.cadastral.ddd.dto.DddSnapshot.DddEntry;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
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
import java.util.List;

/**
 * Cliente único do CSV de DDDs da ANATEL:
 * {@code https://www.anatel.gov.br/dadosabertos/PDA/Codigo_Nacional/PGCN.csv}.
 *
 * <p>CSV com 4 colunas: {@code ibgeCode;state;city;ddd}, separador {@code ;},
 * line break {@code \r\n}, encoding latin-1 (ANATEL serve com
 * {@code responseEncoding: 'binary'} sem declaração — o BrasilAPI
 * desempacota como binário). A primeira linha é o cabeçalho e é descartada.</p>
 *
 * <p>Single provider de propósito: a ANATEL é a fonte canônica do mapeamento
 * código nacional ↔ código IBGE. RAC com hard-TTL 365d cobre downtimes longos
 * (mudanças no quadro de DDDs são raras — última grande revisão em 2006 com
 * a portabilidade numérica).</p>
 */
@Slf4j
@Component
public class AnatelDddClient {

    public static final String PROVIDER_NAME = "ANATEL";
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");

    private static final String DEFAULT_URL =
            "https://www.anatel.gov.br/dadosabertos/PDA/Codigo_Nacional/PGCN.csv";

    private final RestClient restClient;
    private final String csvUrl;

    public AnatelDddClient(RestClient.Builder builder,
                           @Value("${gateway.ddd.anatel.url:" + DEFAULT_URL + "}") String csvUrl) {
        // baseUrl não compartilhado com outros endpoints — URL absoluta no .uri()
        this.restClient = builder.build();
        this.csvUrl = csvUrl;
    }

    @CircuitBreaker(name = "anatelDddCB", fallbackMethod = "fallback")
    public DddSnapshot fetchSnapshot() {
        log.debug("ANATEL DDD snapshot fetch — baixando CSV de {}", csvUrl);
        byte[] csvBytes = restClient.get()
                .uri(csvUrl)
                .accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .body(byte[].class);

        if (csvBytes == null || csvBytes.length == 0) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ANATEL DDD: CSV vazio ou ausente em " + csvUrl);
        }

        String csv = new String(csvBytes, StandardCharsets.ISO_8859_1);
        List<DddEntry> entries = parseCsv(csv);

        if (entries.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ANATEL DDD: nenhuma linha válida após parsing.");
        }

        log.info("ANATEL DDD snapshot atualizado: {} entradas parseadas", entries.size());
        return new DddSnapshot(entries, LocalDate.now(BR_ZONE));
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private DddSnapshot fallback(Throwable cause) {
        log.warn("ANATEL DDD fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ANATEL DDD indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Parse manual sem dependência nova. Formato estável da ANATEL:
     * <pre>
     * ibgeCode;state;city;ddd
     * 1100015;RO;ALTA FLORESTA D'OESTE;69
     * 1100023;RO;ARIQUEMES;69
     * ...
     * </pre>
     * Linha em branco e mal-formada (≠4 colunas) são descartadas em log debug.
     */
    private List<DddEntry> parseCsv(String csv) {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "ANATEL DDD: CSV com menos de 2 linhas, formato inesperado.");
        }
        List<DddEntry> entries = new ArrayList<>(lines.length - 1);
        // Linha 0 = header (descartada).
        for (int i = 1; i < lines.length; i++) {
            DddEntry parsed = parseLine(lines[i]);
            if (parsed != null) entries.add(parsed);
        }
        return entries;
    }

    private DddEntry parseLine(String line) {
        if (line.isBlank()) return null;
        String[] cols = line.split(";", -1);
        if (cols.length != 4) {
            log.debug("ANATEL DDD line skipped (cols={}): {}", cols.length, line);
            return null;
        }
        String ddd = cols[3].trim();
        if (ddd.isEmpty()) {
            return null; // sem DDD a entrada é inutilizável
        }
        return new DddEntry(
                cols[0].trim(),
                cols[1].trim(),
                cols[2].trim(),
                ddd
        );
    }
}
