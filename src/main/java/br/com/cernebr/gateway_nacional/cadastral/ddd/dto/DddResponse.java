package br.com.cernebr.gateway_nacional.cadastral.ddd.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Resposta da consulta de DDD: estado atendido pelo código + lista de
 * cidades. Shape espelhado da BrasilAPI ({@code state, cities[]}) para
 * paridade direta de migração.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "DDD telefônico mapeado para estado e cidades atendidas")
public record DddResponse(
        @Schema(example = "SP", description = "Sigla do estado") String state,
        @Schema(example = "[\"São Paulo\", \"Osasco\", \"Guarulhos\"]") List<String> cities
) {
}
