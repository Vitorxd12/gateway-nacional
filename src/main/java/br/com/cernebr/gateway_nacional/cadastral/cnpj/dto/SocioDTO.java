package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Sócio do QSA (Quadro de Sócios e Administradores).
 *
 * <p>O documento (CPF ou CNPJ) é sempre devolvido <strong>mascarado</strong>
 * em conformidade com a LGPD, no formato {@code ***.NNN.NNN-**} (CPF) ou
 * {@code **.NNN.NNN/NNNN-**} (CNPJ), seguindo a convenção da RFB.</p>
 */
@Schema(name = "Socio",
        description = "Sócio ou administrador no QSA do CNPJ (documento mascarado conforme LGPD).")
public record SocioDTO(
        @Schema(description = "Nome do sócio ou denominação social", example = "MARIA DA SILVA")
        String nome,

        @Schema(description = "CPF/CNPJ mascarado conforme convenção RFB",
                example = "***.456.789-**")
        String cpfCnpjMascarado,

        @Schema(description = "Código + descrição da qualificação do sócio (ex.: 22 - SÓCIO).",
                example = "Sócio-Administrador")
        String qualificacao,

        @Schema(description = "País de origem do sócio (geralmente BRASIL).",
                example = "BRASIL")
        String pais,

        @Schema(description = "Data de entrada do sócio na sociedade.",
                example = "2014-03-10")
        LocalDate dataEntrada
) {
}
