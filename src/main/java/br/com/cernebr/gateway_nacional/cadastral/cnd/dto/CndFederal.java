package br.com.cernebr.gateway_nacional.cadastral.cnd.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "CndFederal",
        description = "Certidão Conjunta Negativa de Débitos relativos a Tributos Federais e Dívida Ativa da União (PGFN/Receita Federal)."
)
public record CndFederal(
        @Schema(description = "Estado da certidão federal. INDISPONIVEL marca degrade do portal PGFN/Receita.",
                example = "NEGATIVA",
                allowableValues = {"NEGATIVA", "POSITIVA", "POSITIVA_COM_EFEITO_NEGATIVO", "INDISPONIVEL"})
        String status,

        @Schema(description = "Data de emissão em ISO-8601", example = "2026-05-13")
        String dataEmissao,

        @Schema(description = "Data de validade em ISO-8601 (180 dias após emissão)", example = "2026-11-09")
        String dataValidade,

        @Schema(description = "URL pública do PDF da certidão",
                example = "https://servicos.receita.fazenda.gov.br/Servicos/certidaointernet/PJ/Emitir/PDF.aspx?ID=abcdef")
        String urlPdf,

        @Schema(description = "Código de controle da certidão (validação on-line)", example = "A1B2.C3D4.E5F6.G7H8")
        String numeroCertidao,

        @Schema(description = "Quando status=INDISPONIVEL, motivo do degrade. Null nos demais casos.",
                example = "PGFN devolveu HTTP 504 — degradação graciosa acionada.")
        String motivoIndisponibilidade
) {
}
