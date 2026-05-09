package br.com.cernebr.gateway_nacional.juridico.sancoes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Sanção administrativa registrada no CEIS (Cadastro Nacional de Empresas
 * Inidôneas e Suspensas) ou no CNEP, ambos publicados pela CGU no Portal
 * da Transparência.
 *
 * <p>Tipos comuns de {@code tipoSancao}: {@code "Inidoneidade"},
 * {@code "Suspensão"}, {@code "Impedimento"}, {@code "Multa"}.</p>
 */
@Schema(name = "SancaoResponse",
        description = "Sanção administrativa publicada pela CGU (CEIS/CNEP) contra a entidade.")
public record SancaoResponse(
        @Schema(description = "CNPJ sancionado (apenas dígitos, 14 caracteres).", example = "00000000000191")
        String cnpj,

        @Schema(description = "Razão social registrada na publicação.", example = "EXEMPLO LTDA")
        String razaoSocial,

        @Schema(description = "Tipo da sanção aplicada.", example = "Inidoneidade")
        String tipoSancao,

        @Schema(description = "Órgão sancionador responsável pela publicação.", example = "TCU")
        String orgaoSancionador,

        @Schema(description = "Data de início da vigência da sanção.", example = "2024-03-15", format = "date")
        LocalDate dataInicio,

        @Schema(description = "Data de fim da vigência da sanção (pode ser null para indeterminadas).", example = "2026-03-14", format = "date")
        LocalDate dataFim
) {
}
