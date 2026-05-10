package br.com.cernebr.gateway_nacional.financeiro.pix.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Instituição participante do arranjo PIX (BACEN).
 *
 * <p>Shape unificado entre os dois providers. Ambos hoje retornam
 * {@code inicio_operacao} como {@code null} — o CSV oficial do BCB não
 * publica essa coluna, e a BrasilAPI propaga {@code null}. O campo é
 * mantido para compatibilidade com a API pública da BrasilAPI e fica
 * automaticamente omitido do JSON via {@link com.fasterxml.jackson.annotation.JsonInclude#NON_NULL}.</p>
 *
 * <p>{@code modalidadeParticipacao}: {@code PDCT} (direto) | {@code PIDR}
 * (indireto). {@code tipoParticipacao}: {@code DRCT} (titular) | {@code IDRT}
 * (subordinado a um direto).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Instituição participante do arranjo PIX")
public record PixParticipanteResponse(
        @Schema(example = "00000000", description = "ISPB de 8 dígitos") String ispb,
        @Schema(example = "BANCO DO BRASIL S.A.") String nome,
        @JsonProperty("nome_reduzido")
        @Schema(name = "nome_reduzido", example = "BCO DO BRASIL") String nomeReduzido,
        @JsonProperty("modalidade_participacao")
        @Schema(name = "modalidade_participacao", example = "PDCT",
                allowableValues = {"PDCT", "PIDR"}) String modalidadeParticipacao,
        @JsonProperty("tipo_participacao")
        @Schema(name = "tipo_participacao", example = "DRCT",
                allowableValues = {"DRCT", "IDRT"}) String tipoParticipacao,
        @JsonProperty("inicio_operacao")
        @Schema(name = "inicio_operacao", example = "2020-11-03T09:30:00-03:00") OffsetDateTime inicioOperacao
) {
}
