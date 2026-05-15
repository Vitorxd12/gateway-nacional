package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Breakdown of the geographic sampling behind a {@link DadosMercado} snapshot.
 *
 * <p>Records <i>how</i> the price sample was collected so a consumer can judge
 * the statistical weight of {@code precoMedioRegional}: a regional average
 * computed over 3 listings is far weaker than one over 60. When the caller
 * did not pass {@code uf}/{@code cidade}, {@code escopoBusca} is
 * {@code "NACIONAL"} and {@code volumeAnunciosLocais} mirrors the national
 * total — the regionalization layer simply did not engage.</p>
 */
@Schema(name = "DetalhesAmostragem",
        description = "Detalhamento da amostragem geográfica: escopo aplicado e volume de anúncios locais coletados.")
public record DetalhesAmostragem(
        @Schema(description = "Escopo geográfico efetivamente aplicado na raspagem.",
                example = "REGIONAL:SP/Campinas")
        String escopoBusca,

        @Schema(description = "Volume de anúncios coletados dentro do recorte geográfico solicitado.",
                example = "18")
        int volumeAnunciosLocais,

        @Schema(description = "Quantidade de marketplaces consultados nesta requisição.", example = "2")
        int marketplacesConsultados,

        @Schema(description = "Quantidade de marketplaces que retornaram ao menos um anúncio válido.", example = "2")
        int marketplacesComRetorno
) {

    public static final String ESCOPO_NACIONAL = "NACIONAL";

    /**
     * Builds the {@code escopoBusca} label from the geographic inputs.
     * {@code NACIONAL} when no UF was supplied; {@code REGIONAL:SP} for a
     * state-only filter; {@code REGIONAL:SP/Campinas} when a city is added.
     */
    public static String escopo(String uf, String cidade) {
        if (uf == null || uf.isBlank()) {
            return ESCOPO_NACIONAL;
        }
        if (cidade == null || cidade.isBlank()) {
            return "REGIONAL:" + uf;
        }
        return "REGIONAL:" + uf + "/" + cidade;
    }
}
