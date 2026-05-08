package br.com.cernebr.gateway_nacional.fiscal.ncm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Unified NCM (Nomenclatura Comum do Mercosul) entry exposed by the
 * Gateway, regardless of which upstream provider (BrasilAPI, Siscomex)
 * actually resolved the lookup.
 *
 * <p>Every field is nullable on the contract surface because the upstream
 * payload is itself sparse: act metadata ({@code tipoAto}, {@code numeroAto},
 * {@code anoAto}) is only present on entries whose creation/revision is
 * tied to a public Camex resolution; older entries inherited from the
 * pre-2022 nomenclature carry only the code/description/validity range.
 * Forcing those fields to be non-null would either break the schema or
 * fabricate values — both worse than honest nullability.</p>
 *
 * <p>{@code dataInicio} / {@code dataFim} use {@link LocalDate} (no time
 * component) — the upstream serializes them as ISO {@code YYYY-MM-DD}.
 * The sentinel {@code 9999-12-31} from BrasilAPI's response means
 * "indefinitely valid" — we surface it verbatim so consumers can decide
 * whether to display "—" or the literal date.</p>
 */
@Schema(name = "NcmResponse",
        description = "Entrada NCM (Nomenclatura Comum do Mercosul) unificada pelo Gateway Nacional.")
public record NcmResponse(
        @Schema(description = "Código NCM em formato canônico do Mercosul (com pontos: NN.NN.NN)",
                example = "3305.10.00")
        String codigo,

        @Schema(description = "Descrição oficial do código (verbatim do upstream — pode iniciar com hífen quando é sub-item)",
                example = "- Xampus")
        String descricao,

        @Schema(description = "Data de início da vigência do código (ISO yyyy-MM-dd)",
                example = "2022-04-01")
        LocalDate dataInicio,

        @Schema(description = "Data de término da vigência (9999-12-31 indica vigência indeterminada)",
                example = "9999-12-31")
        LocalDate dataFim,

        @Schema(description = "Tipo do ato normativo que estabeleceu o código (nullable em entradas pré-2022)",
                example = "Res Camex")
        String tipoAto,

        @Schema(description = "Número do ato normativo (nullable em entradas pré-2022)",
                example = "272")
        String numeroAto,

        @Schema(description = "Ano do ato normativo (nullable em entradas pré-2022)",
                example = "2021")
        Integer anoAto
) {
}
