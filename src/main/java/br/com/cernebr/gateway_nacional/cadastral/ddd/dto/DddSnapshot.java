package br.com.cernebr.gateway_nacional.cadastral.ddd.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Snapshot completo do CSV da ANATEL. O cache armazena uma única lista de
 * todas as linhas (~5.5k entradas, ~67 DDDs distintos); o lookup por DDD é
 * feito em memória — economiza re-baixar o CSV a cada consulta.
 *
 * <p>Mesma estratégia dos snapshots CVM: snapshot único cacheado, queries
 * filtram em memória.</p>
 */
@Schema(name = "DddSnapshot", hidden = true)
public record DddSnapshot(
        List<DddEntry> entries,
        LocalDate dataReferencia
) {

    /**
     * Linha bruta do CSV ANATEL (antes do agrupamento por DDD que vira
     * {@link DddResponse}).
     */
    public record DddEntry(String ibgeCode, String state, String city, String ddd) {
    }
}
