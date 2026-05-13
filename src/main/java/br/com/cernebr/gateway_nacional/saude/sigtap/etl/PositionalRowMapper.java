package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import br.com.cernebr.gateway_nacional.saude.sigtap.jdbc.SigtapJdbc;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cid;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Procedimento;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCid;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Acumula linhas parseadas em buffers e dispara batch-insert quando
 * o buffer atinge {@link #BATCH_SIZE}. Garante JDBC batch eficiente
 * para os arquivos grandes (rl_procedimento_cbo tem 200k+ linhas).
 */
public class PositionalRowMapper {

    private static final int BATCH_SIZE = 500;

    private final long datasetId;
    private final String competencia;

    private final List<Procedimento> bufProc = new ArrayList<>(BATCH_SIZE);
    private final List<Cbo> bufCbo = new ArrayList<>(BATCH_SIZE);
    private final List<Cid> bufCid = new ArrayList<>(BATCH_SIZE);
    private final List<ProcedimentoCbo> bufProcCbo = new ArrayList<>(BATCH_SIZE);
    private final List<ProcedimentoCid> bufProcCid = new ArrayList<>(BATCH_SIZE);

    public PositionalRowMapper(long datasetId, String competencia) {
        this.datasetId = datasetId;
        this.competencia = competencia;
    }

    public void bufferProcedimento(Map<String, String> row, SigtapJdbc jdbc) {
        bufProc.add(new Procedimento(
                datasetId,
                row.get("codigo"),
                row.get("nome"),
                mapComplexidade(row.get("complexidade")),
                mapSexo(row.get("sexo")),
                parseIntOrNull(row.get("idadeMinimaDias")),
                parseIntOrNull(row.get("idadeMaximaDias")),
                parseIntOrNull(row.get("quantidadeMaxima")),
                row.get("tipoFinanciamento"),
                parseValor(row.get("valorSa")),
                parseValor(row.get("valorSh")),
                parseValor(row.get("valorSp")),
                null, null, null,
                competencia
        ));
        if (bufProc.size() >= BATCH_SIZE) flushProcedimentos(jdbc);
    }

    public void bufferCbo(Map<String, String> row, SigtapJdbc jdbc) {
        bufCbo.add(new Cbo(datasetId, row.get("codigo"), row.get("nome")));
        if (bufCbo.size() >= BATCH_SIZE) flushCbos(jdbc);
    }

    public void bufferCid(Map<String, String> row, SigtapJdbc jdbc) {
        bufCid.add(new Cid(datasetId, row.get("codigo"), row.get("nome")));
        if (bufCid.size() >= BATCH_SIZE) flushCids(jdbc);
    }

    public void bufferProcCbo(Map<String, String> row, SigtapJdbc jdbc) {
        bufProcCbo.add(new ProcedimentoCbo(datasetId, row.get("procedimentoCodigo"), row.get("cboCodigo")));
        if (bufProcCbo.size() >= BATCH_SIZE) flushProcCbo(jdbc);
    }

    public void bufferProcCid(Map<String, String> row, SigtapJdbc jdbc) {
        bufProcCid.add(new ProcedimentoCid(datasetId,
                row.get("procedimentoCodigo"),
                row.get("cidCodigo"),
                "S".equalsIgnoreCase(row.get("obrigatorio"))));
        if (bufProcCid.size() >= BATCH_SIZE) flushProcCid(jdbc);
    }

    public void flushAll(SigtapJdbc jdbc) {
        flushCbos(jdbc);
        flushCids(jdbc);
        flushProcedimentos(jdbc);
        flushProcCbo(jdbc);
        flushProcCid(jdbc);
    }

    private void flushProcedimentos(SigtapJdbc jdbc) {
        if (!bufProc.isEmpty()) {
            jdbc.batchInsertProcedimentos(datasetId, bufProc);
            bufProc.clear();
        }
    }

    private void flushCbos(SigtapJdbc jdbc) {
        if (!bufCbo.isEmpty()) {
            jdbc.batchInsertCbos(datasetId, bufCbo);
            bufCbo.clear();
        }
    }

    private void flushCids(SigtapJdbc jdbc) {
        if (!bufCid.isEmpty()) {
            jdbc.batchInsertCids(datasetId, bufCid);
            bufCid.clear();
        }
    }

    private void flushProcCbo(SigtapJdbc jdbc) {
        if (!bufProcCbo.isEmpty()) {
            jdbc.batchInsertProcCbo(datasetId, bufProcCbo);
            bufProcCbo.clear();
        }
    }

    private void flushProcCid(SigtapJdbc jdbc) {
        if (!bufProcCid.isEmpty()) {
            jdbc.batchInsertProcCid(datasetId, bufProcCid);
            bufProcCid.clear();
        }
    }

    private static Integer parseIntOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal parseValor(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        // DataSUS publica em centavos sem ponto decimal (10 dígitos).
        try {
            long centavos = Long.parseLong(raw.trim());
            return BigDecimal.valueOf(centavos).movePointLeft(2);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String mapComplexidade(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim()) {
            case "1" -> "ATENCAO_BASICA";
            case "2" -> "MEDIA";
            case "3" -> "ALTA";
            case "4" -> "NAO_SE_APLICA";
            default -> null;
        };
    }

    private static String mapSexo(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim()) {
            case "1" -> "MASCULINO";
            case "2" -> "FEMININO";
            case "I", "3" -> "AMBOS";
            default -> "AMBOS";
        };
    }
}
