package br.com.cernebr.gateway_nacional.fiscal.cest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * CEST (Código Especificador da Substituição Tributária) entry.
 *
 * <p>The CEST identifies merchandise potentially subject to ICMS-ST
 * (Substituição Tributária) and is required on every NF-e line whose
 * NCM is listed in Convênio ICMS 142/2018. Each CEST is a 7-digit code
 * with three parts (SS-III-DD): segment, item and product specification.
 *
 * <p>Cardinality is N:N — a single NCM may be referenced by several
 * CESTs (different segments), and a single CEST may apply to several
 * NCMs. The lookup most ERPs care about is "given my product's NCM,
 * which CESTs are candidates?" — that's why this gateway exposes a
 * dedicated {@code /ncm/{ncm}} route returning a list.
 *
 * <p>The table is essentially static (Convênio ICMS), small (~1k
 * entries) and changes rarely, so it is served from an in-memory
 * snapshot — same strategy used for CFOP. Lookup is O(1); no upstream
 * call, no Redis cache.
 */
@Schema(name = "CestResponse",
        description = "Código Especificador da Substituição Tributária (CEST) servido in-memory pelo Gateway Nacional.")
public record CestResponse(
        @Schema(description = "Código CEST (7 dígitos, sem pontuação)", example = "0100100")
        String cest,

        @Schema(description = "NCM associado ao CEST (sem pontuação). " +
                "Pode ser parcial (somente capítulo/posição) quando o CEST cobre uma família inteira de NCMs.",
                example = "38151210")
        String ncm,

        @Schema(description = "Descrição oficial do CEST (verbatim do Convênio ICMS 142/2018 e atualizações)",
                example = "Catalisadores em colmeia cerâmica ou metálica para conversão catalítica de gases de escape de veículos e outros catalisadores")
        String descricao
) {
}
