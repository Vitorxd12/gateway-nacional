package br.com.cernebr.gateway_nacional.juridico.cnd.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Resultado da emissão/validação de uma Certidão Negativa de Débitos.
 *
 * <p>{@code status} é a string canônica devolvida pelo emissor — tipicamente
 * {@code "NEGATIVA"} (sem débitos), {@code "POSITIVA COM EFEITOS DE NEGATIVA"}
 * (com débitos suspensos) ou {@code "POSITIVA"} (com débitos exigíveis).
 * O ERP consumidor decide a regra de negócio (impedir contrato, exigir
 * regularização etc).</p>
 */
@Schema(name = "CndResponse",
        description = "Certidão Negativa de Débitos emitida/validada via sidecar de scraping.")
public record CndResponse(
        @Schema(description = "CNPJ titular da certidão (apenas dígitos).", example = "00000000000191")
        String cnpj,

        @Schema(description = "Tipo da certidão emitida.", example = "RFB",
                allowableValues = {"RFB", "FGTS", "TST"})
        String tipoCertidao,

        @Schema(description = "Status canônico devolvido pelo emissor.", example = "NEGATIVA")
        String status,

        @Schema(description = "Data de emissão da certidão.", example = "2026-05-08", format = "date")
        LocalDate dataEmissao,

        @Schema(description = "Validade final da certidão emitida.", example = "2026-11-04", format = "date")
        LocalDate validade,

        @Schema(description = "Código de controle/autenticidade emitido para verificação posterior no portal de origem.", example = "AB12.CD34.EF56.7890")
        String codigoControle
) {
}
