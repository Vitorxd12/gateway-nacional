package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Envelope canônico da rota de exportação. Substitui o layout posicional
 * legado do DataSUS por um JSON denormalizado: o cliente consome um único
 * documento e tem tudo que precisa (procedimentos + relacionamentos + valores)
 * para um caching local sem necessidade de joins.
 */
@Schema(
        name = "SigtapExportResponse",
        description = "Dump JSON mastigado da competência ativa. Substitui o pacote posicional do DataSUS por payload pronto para consumo."
)
public record SigtapExportResponse(
        @Schema(description = "Competência (AAAAMM) exportada", example = "202605")
        String competencia,

        @Schema(description = "Carimbo de geração do dump", example = "2026-05-13T03:42:11Z")
        OffsetDateTime geradoEm,

        @Schema(description = "Total de procedimentos no dump", example = "4528")
        int totalProcedimentos,

        @Schema(description = "Lista de procedimentos com taxonomia e valores embutidos")
        List<SigtapProcedimentoResponse> procedimentos,

        @Schema(description = "Mapa código→nome dos CBOs presentes no recorte SIGTAP")
        Map<String, String> cbos,

        @Schema(description = "Mapa código→nome dos CID-10 presentes")
        Map<String, String> cids,

        @Schema(description = "Relação procedimento → lista de CBOs que faturam")
        Map<String, List<String>> procedimentoCbo,

        @Schema(description = "Relação procedimento → lista de CIDs autorizados")
        Map<String, List<String>> procedimentoCid
) {
}
