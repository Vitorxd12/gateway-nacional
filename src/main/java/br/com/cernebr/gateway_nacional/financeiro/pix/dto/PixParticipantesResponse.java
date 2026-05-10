package br.com.cernebr.gateway_nacional.financeiro.pix.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Envelope da listagem de participantes do PIX.
 *
 * <p>Diferente da BrasilAPI (que retorna {@code List<>} cru), embrulhamos
 * em um record com metadados — {@code total}, {@code dataReferencia} e
 * {@code fonte} — porque:</p>
 * <ol>
 *   <li>Cliente ganha visibilidade da fonte que respondeu (BrasilAPI ou
 *       fallback BCB) e da data efetiva do snapshot — quando o BCB serviu
 *       de fallback após dia(s) sem publicação, {@code dataReferencia} pode
 *       ser anterior a "hoje", e o consumidor precisa saber para auditoria;</li>
 *   <li>Permite cachear via {@link br.com.cernebr.gateway_nacional.config.RefreshAheadCache}
 *       (que rejeita {@code List<>} cru — ver javadoc do {@code CachedEntry}).</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Envelope com a lista completa de participantes do PIX")
public record PixParticipantesResponse(
        @Schema(example = "856", description = "Quantidade de participantes na resposta") int total,
        @JsonProperty("data_referencia")
        @Schema(name = "data_referencia", example = "2026-05-09",
                description = "Data efetiva do snapshot — pode ser anterior a hoje quando o BCB foi consultado num feriado") LocalDate dataReferencia,
        @Schema(example = "BrasilAPI", description = "Provider que efetivamente respondeu") String fonte,
        List<PixParticipanteResponse> participantes
) {
}
