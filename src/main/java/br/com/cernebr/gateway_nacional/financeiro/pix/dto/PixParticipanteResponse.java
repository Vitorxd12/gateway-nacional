package br.com.cernebr.gateway_nacional.financeiro.pix.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Instituição participante do arranjo PIX (BACEN).
 *
 * <p>Shape unificado entre os dois providers: a {@code BrasilAPI} entrega
 * exatamente este formato JSON; o cliente do CSV oficial do BCB normaliza
 * a data brasileira ({@code dd/MM/yyyy HH:mm}) para {@link OffsetDateTime}
 * em {@code America/Sao_Paulo}, mantendo o offset explícito para o
 * consumidor não precisar saber o fuso.</p>
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
