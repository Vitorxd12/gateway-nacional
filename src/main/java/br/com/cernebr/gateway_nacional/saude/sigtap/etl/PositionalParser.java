package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parser stream-oriented dos arquivos posicionais do DataSUS.
 *
 * <p><b>Por que streaming:</b> o {@code tb_procedimento.txt} tem ~4.500
 * linhas e ~340 bytes por linha (~1.5MB); o {@code rl_procedimento_cbo.txt}
 * passa de 200k linhas. Carregar tudo em memória é cabível mas desnecessário.
 * Aqui usamos {@code BufferedReader.readLine()} e entregamos linha a linha
 * via {@link Consumer}, deixando o consumidor decidir agrupamento em
 * batches JDBC (200 inserts por roundtrip).</p>
 *
 * <p><b>Encoding:</b> DataSUS publica em ISO-8859-1 ("Latin1") com acentuação.
 * Forçar UTF-8 corromperia "AÇÃO". O default {@link StandardCharsets#ISO_8859_1}
 * é correto para 100% dos arquivos do pacote mensal.</p>
 */
@Slf4j
@Component
public class PositionalParser {

    private static final Charset DATASUS_CHARSET = StandardCharsets.ISO_8859_1;

    private final SigtapLogService logService;

    public PositionalParser(SigtapLogService logService) {
        this.logService = logService;
    }

    public void parse(InputStream stream, PositionalLayout layout,
                      Consumer<Map<String, String>> rowHandler) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, DATASUS_CHARSET))) {
            String line;
            int parsed = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, String> row = new HashMap<>(layout.columns().size() * 2);
                for (PositionalLayout.Column col : layout.columns()) {
                    row.put(col.name(), col.extract(line));
                }
                rowHandler.accept(row);
                parsed++;
                
                if (parsed % 10000 == 0) {
                    logService.log(String.format("[SIGTAP ETL] -> [%s] ... %d linhas parseadas", layout.tableName(), parsed));
                }
            }
            logService.log(String.format("[SIGTAP ETL] -> [%s] Concluído: %d linhas processadas.", layout.tableName(), parsed));
            log.info("[PositionalParser] {}: {} linhas processadas", layout.tableName(), parsed);
        } catch (IOException ex) {
            throw new SigtapEtlException("Falha ao parsear " + layout.tableName() + ": " + ex.getMessage(), ex);
        }
    }
}
