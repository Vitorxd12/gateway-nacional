package br.com.cernebr.gateway_nacional.cadastral.ibge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Município brasileiro. Shape unificado entre os providers (gov + dados-abertos-br).
 * O nome é normalizado para uppercase no service para que diferentes fontes
 * convirjam para a mesma representação independente do casing original.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Município brasileiro com código IBGE")
public record MunicipioResponse(
        @Schema(example = "SÃO PAULO") String nome,
        @JsonProperty("codigo_ibge")
        @Schema(name = "codigo_ibge", example = "3550308") String codigoIbge
) {
}
