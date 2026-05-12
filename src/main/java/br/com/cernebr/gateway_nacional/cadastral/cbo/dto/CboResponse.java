package br.com.cernebr.gateway_nacional.cadastral.cbo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ocupação da Classificação Brasileira de Ocupações (CBO)")
public record CboResponse(
        @Schema(example = "225125") String codigo,
        @Schema(example = "MÉDICO CLÍNICO") String titulo
) {}
