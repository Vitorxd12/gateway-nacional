package br.com.cernebr.gateway_nacional.financeiro.cvm.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CorretoraResponse;
import br.com.cernebr.gateway_nacional.financeiro.cvm.dto.CvmCorretorasSnapshot;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Cliente único do dump de intermediários da CVM:
 * {@code https://dados.cvm.gov.br/dados/INTERMED/CAD/DADOS/cad_intermed.zip}.
 *
 * <p>O ZIP contém um único CSV ({@code cad_intermed.csv}) com todos os
 * intermediários autorizados (corretoras, distribuidoras, agentes autônomos,
 * etc). O cliente filtra apenas {@code TIPO=CORRETORAS} e mapeia 27 colunas
 * — os índices vagos no destructuring da BrasilAPI ({@code [, , status, ...]})
 * correspondem a campos da CVM que não interessam ao consumidor (data de
 * cancelamento, motivo, setor, controle acionário, DDD).</p>
 *
 * <h2>Encoding e parsing</h2>
 * <p>CSV em {@code latin1} (Windows-1252 funcionalmente equivalente),
 * separador {@code ;}, line break {@code \r\n} (oficial CVM). Parser manual
 * sem dependência nova — formato estável há décadas.</p>
 */
@Slf4j
@Component
public class CvmCorretorasClient {

    public static final String PROVIDER_NAME = "CVM-Corretoras";
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");

    private static final String DEFAULT_URL =
            "https://dados.cvm.gov.br/dados/INTERMED/CAD/DADOS/cad_intermed.zip";
    private static final Pattern CSV_ENTRY = Pattern.compile("(?i)cad_intermed\\.csv$");
    private static final String CORRETORAS_TYPE = "CORRETORAS";
    private static final String DIGITS_ONLY = "\\D";

    /**
     * Mínimo de colunas para uma linha ser considerada parseável. O CSV oficial
     * tem 27 colunas; abaixo disso indica linha truncada/corrompida e é
     * descartada silenciosamente em log debug.
     */
    private static final int MIN_REQUIRED_COLS = 27;

    private final RestClient restClient;
    private final String zipUrl;

    public CvmCorretorasClient(RestClient.Builder builder,
                               @Value("${gateway.cvm.corretoras.url:" + DEFAULT_URL + "}") String zipUrl) {
        // Bypass do baseUrl porque o endpoint da CVM já é uma URL absoluta
        // específica para este arquivo — não compartilha base com outros endpoints.
        this.restClient = builder.build();
        this.zipUrl = zipUrl;
    }

    @CircuitBreaker(name = "cvmCorretorasCB", fallbackMethod = "fallback")
    public CvmCorretorasSnapshot fetchSnapshot() {
        log.debug("CVM corretoras snapshot fetch — baixando ZIP de {}", zipUrl);
        byte[] zipBytes = restClient.get()
                .uri(zipUrl)
                .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.parseMediaType("application/zip"))
                .retrieve()
                .body(byte[].class);

        if (zipBytes == null || zipBytes.length == 0) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CVM corretoras: ZIP vazio ou ausente em " + zipUrl);
        }

        String csv = extractCsvFromZip(zipBytes);
        List<CorretoraResponse> corretoras = parseCsv(csv);

        if (corretoras.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CVM corretoras: nenhuma linha CORRETORAS encontrada após parsing.");
        }

        log.info("CVM corretoras snapshot atualizado: {} corretoras parseadas", corretoras.size());
        return new CvmCorretorasSnapshot(corretoras, LocalDate.now(BR_ZONE));
    }

    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private CvmCorretorasSnapshot fallback(Throwable cause) {
        log.warn("CVM corretoras fallback acionado: {}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "CVM corretoras indisponível ou Circuit Breaker aberto.", cause);
    }

    private String extractCsvFromZip(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (CSV_ENTRY.matcher(entry.getName()).find()) {
                    byte[] csvBytes = zis.readAllBytes();
                    return new String(csvBytes, StandardCharsets.ISO_8859_1);
                }
            }
        } catch (IOException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao extrair cad_intermed.csv do ZIP: " + ex.getMessage(), ex);
        }
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "ZIP da CVM não contém entrada cad_intermed.csv");
    }

    /**
     * Mapeamento de colunas (descoberto via {@code services/cvm/corretoras.js}
     * da BrasilAPI):
     * <pre>
     *  0: type                  (filter == CORRETORAS)
     *  1: cnpj                  (limpa não-dígitos)
     *  2: nome social
     *  3: nome comercial
     *  4: data registro
     *  5,6: descartados (data/motivo cancelamento)
     *  7: status
     *  8: data inicio status
     *  9: codigo CVM
     * 10,11: descartados (setor/controle acionário)
     * 12: valor patrimônio líquido
     * 13: data divulgação patrimônio
     * 14: descartado (tipo endereço)
     * 15: logradouro
     * 16: complemento
     * 17: bairro
     * 18: cidade
     * 19: estado
     * 20: pais
     * 21: cep
     * 22: descartado (DDD telefone)
     * 23: telefone
     * 24,25: descartados (DDD/fax)
     * 26: email
     * </pre>
     */
    private List<CorretoraResponse> parseCsv(String csv) {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CVM corretoras: CSV com menos de 2 linhas, formato inesperado.");
        }
        // Linha 0 = header (nomes em PT-BR, não validados — confiamos no CVM
        // não mudar ordem; se mudarem, o filtro CORRETORAS na col 0 quebra
        // ruidosamente e o snapshot vira erro de "lista vazia" detectável).

        List<CorretoraResponse> corretoras = new ArrayList<>(2048);
        for (int i = 1; i < lines.length; i++) {
            CorretoraResponse parsed = parseLine(lines[i]);
            if (parsed != null) corretoras.add(parsed);
        }
        return corretoras;
    }

    private CorretoraResponse parseLine(String line) {
        if (line.isBlank()) return null;
        String[] cols = line.split(";", -1);
        if (cols.length < MIN_REQUIRED_COLS) {
            log.debug("CVM corretoras line skipped (cols={} < {}): {}", cols.length, MIN_REQUIRED_COLS, line);
            return null;
        }
        if (!CORRETORAS_TYPE.equals(cols[0].trim())) {
            return null; // outros tipos de intermediário (DTVM, agente autônomo, etc)
        }
        if (cols[1].isBlank()) {
            return null; // linha sem CNPJ é inválida
        }

        return new CorretoraResponse(
                cols[1].replaceAll(DIGITS_ONLY, ""),
                trim(cols[2]),
                trim(cols[3]),
                trim(cols[7]),
                trim(cols[26]),
                trim(cols[23]),
                trim(cols[21]),
                trim(cols[20]),
                trim(cols[19]),
                trim(cols[18]),
                trim(cols[17]),
                trim(cols[16]),
                trim(cols[15]),
                trim(cols[13]),
                trim(cols[12]),
                trim(cols[9]),
                trim(cols[8]),
                trim(cols[4])
        );
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
