package br.com.cernebr.gateway_nacional.cadastral.cnd.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "CndFgts",
        description = "Certificado de Regularidade do FGTS (CRF), emitido pela Caixa Econômica Federal via portal Consulta Regularidade do Empregador (ASP.NET WebForms)."
)
public record CndFgts(
        @Schema(description = "Estado do CRF. INDISPONIVEL marca degrade do portal Caixa.",
                example = "NEGATIVA",
                allowableValues = {"NEGATIVA", "POSITIVA", "INDISPONIVEL"})
        String status,

        @Schema(description = "Data de emissão em ISO-8601", example = "2026-05-13")
        String dataEmissao,

        @Schema(description = "Data de validade em ISO-8601 (30 dias após emissão para o FGTS)", example = "2026-06-12")
        String dataValidade,

        @Schema(description = "URL pública do PDF do CRF na infra Caixa",
                example = "https://consulta-crf.caixa.gov.br/consultacrf/pages/listaEmpregador.jsf?token=abc123")
        String urlPdf,

        @Schema(description = "Número do CRF (controle interno Caixa)", example = "2026051300012345/RJ")
        String numeroCertidao,

        @Schema(description = "Quando status=INDISPONIVEL, motivo do degrade. Null nos demais casos.",
                example = "Portal Caixa devolveu HTTP 503 — degradação graciosa acionada.")
        String motivoIndisponibilidade
) {
}
