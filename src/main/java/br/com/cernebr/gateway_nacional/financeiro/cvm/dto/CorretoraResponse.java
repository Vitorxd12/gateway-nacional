package br.com.cernebr.gateway_nacional.financeiro.cvm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corretora de valores autorizada pela CVM. Shape derivado do arquivo
 * {@code cad_intermed.csv} (CVM intermediários, filtrado por
 * {@code TIPO=CORRETORAS}).
 *
 * <p>Mantém o snake_case do JSON da BrasilAPI para consumidores migrarem
 * sem ajustar parser. Campos opcionais ({@code email}, {@code telefone},
 * {@code valor_patrimonio_liquido}) ficam ausentes via
 * {@link JsonInclude#NON_NULL} quando vazios — comum em registros legados
 * do dump da CVM.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Corretora de valores autorizada pela CVM")
public record CorretoraResponse(
        @Schema(example = "76621457000185") String cnpj,
        @JsonProperty("nome_social")
        @Schema(name = "nome_social", example = "XP INVESTIMENTOS CCTVM S.A.") String nomeSocial,
        @JsonProperty("nome_comercial")
        @Schema(name = "nome_comercial", example = "XP INVESTIMENTOS") String nomeComercial,
        @Schema(example = "EM FUNCIONAMENTO NORMAL") String status,
        String email,
        String telefone,
        String cep,
        String pais,
        String uf,
        String municipio,
        String bairro,
        String complemento,
        String logradouro,
        @JsonProperty("data_patrimonio_liquido")
        @Schema(name = "data_patrimonio_liquido", example = "2025-09-30") String dataPatrimonioLiquido,
        @JsonProperty("valor_patrimonio_liquido")
        @Schema(name = "valor_patrimonio_liquido", example = "1234567.89") String valorPatrimonioLiquido,
        @JsonProperty("codigo_cvm")
        @Schema(name = "codigo_cvm", example = "00308") String codigoCvm,
        @JsonProperty("data_inicio_situacao")
        @Schema(name = "data_inicio_situacao", example = "2009-12-01") String dataInicioSituacao,
        @JsonProperty("data_registro")
        @Schema(name = "data_registro", example = "2008-06-01") String dataRegistro
) {
}
