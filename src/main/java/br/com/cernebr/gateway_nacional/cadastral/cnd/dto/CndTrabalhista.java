package br.com.cernebr.gateway_nacional.cadastral.cnd.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "CndTrabalhista",
        description = "Certidão Negativa de Débitos Trabalhistas (CNDT) emitida pelo TST conforme Lei 12.440/2011."
)
public record CndTrabalhista(
        @Schema(description = "Estado da certidão. INDISPONIVEL é usado quando o nó TST degradou.",
                example = "NEGATIVA",
                allowableValues = {"NEGATIVA", "POSITIVA", "POSITIVA_COM_EFEITO_NEGATIVO", "INEXISTENTE", "INDISPONIVEL"})
        String status,

        @Schema(description = "Data de emissão em ISO-8601", example = "2026-05-13")
        String dataEmissao,

        @Schema(description = "Data de validade em ISO-8601 (180 dias após emissão)", example = "2026-11-09")
        String dataValidade,

        @Schema(description = "URL pública do PDF da certidão na infra TST",
                example = "https://certidao.tst.jus.br/certidao/PDF/000000000019120260513.pdf")
        String urlPdf,

        @Schema(description = "Número da certidão (controle interno TST)", example = "184523456/2026")
        String numeroCertidao,

        @Schema(description = "Quando status=INDISPONIVEL, motivo do degrade. Null nos demais casos.",
                example = "TST 503 — timeout > 8s na emissão.")
        String motivoIndisponibilidade
) {
}
