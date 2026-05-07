package br.com.cernebr.gateway_nacional.placa.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unified vehicle-by-placa payload exposed by the Gateway, regardless of which
 * upstream provider (WDApi, Keplaca, PlacaFipe) actually resolved the lookup.
 *
 * <p><b>Privacy:</b> {@code chassi} is always returned masked
 * ({@code "***1234"} — last 4 digits only) or {@code null}. The full chassis
 * never crosses the gateway boundary, even in logs or cache. Masking happens
 * inside the Anti-Corruption Layer of each client, before the response shape
 * is constructed.</p>
 *
 * <p><b>FIPE enrichment:</b> {@code codigoFipe} is populated only by providers
 * that publish that mapping ({@code PlacaFipeScraperClient} as of today).
 * Token-based providers (WDApi, Keplaca) leave it {@code null}. Downstream
 * consumers — most notably the Avaliação domain — can pipe a non-null
 * {@code codigoFipe} straight into the FIPE quote without asking the caller
 * to supply it manually.</p>
 */
@Schema(name = "PlacaResponse", description = "Identificação de veículo a partir da placa, em formato unificado pelo Gateway Nacional.")
public record PlacaResponse(
        @Schema(description = "Placa normalizada (uppercase, sem hífen)", example = "ABC1D23")
        String placa,

        @Schema(description = "Marca do veículo", example = "VOLKSWAGEN")
        String marca,

        @Schema(description = "Modelo (versão completa)", example = "GOL 1.0 FLEX")
        String modelo,

        @Schema(description = "Ano de fabricação", example = "2010")
        int anoFabricacao,

        @Schema(description = "Ano modelo", example = "2011")
        int anoModelo,

        @Schema(description = "Chassi mascarado (últimos 4 dígitos) ou null", example = "***3456")
        String chassi,

        @Schema(description = "Município de emplacamento", example = "Guarulhos")
        String municipio,

        @Schema(description = "Sigla da UF de emplacamento", example = "SP")
        String uf,

        @Schema(description = "Código FIPE no padrão 000000-0 quando o provedor publica essa associação. **null** quando resolvido por WDApi ou Keplaca (não expõem FIPE).",
                example = "005340-0", nullable = true)
        String codigoFipe
) {
}
