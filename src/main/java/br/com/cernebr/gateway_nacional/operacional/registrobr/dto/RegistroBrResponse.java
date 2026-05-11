package br.com.cernebr.gateway_nacional.operacional.registrobr.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Resposta unificada da consulta de disponibilidade de domínio {@code .br}
 * via Registro.br / NIC.br.
 *
 * <p>O Registro.br responde com um payload bastante denso (ajax/avail) e a
 * BrasilAPI normaliza-o. O contrato aqui combina o melhor dos dois — preserva
 * os campos canônicos {@code status}, {@code status_publication} e
 * {@code suggestions}, mas com nomes camelCase consistentes com o resto do
 * gateway.</p>
 */
@Schema(name = "RegistroBrResponse",
        description = "Resultado da consulta de disponibilidade de domínio .br no Registro.br/NIC.br.")
public record RegistroBrResponse(
        @Schema(description = "Domínio consultado em forma canônica (lowercase, sem espaços).",
                example = "google.com.br")
        String dominio,

        @Schema(description = "Resultado bruto do Registro.br — exemplos: AVAILABLE, UNAVAILABLE, EXPIRED, WAITING.",
                example = "UNAVAILABLE")
        String status,

        @Schema(description = "Indica se o domínio está livre para registro (true) ou já alocado/em processo (false).",
                example = "false")
        boolean disponivel,

        @Schema(description = "Mensagem auxiliar do upstream (ex.: razão pela qual o domínio está em WAITING).",
                example = "REGISTERED")
        String motivo,

        @Schema(description = "Status de publicação do domínio no DNS público (PUBLISHED, NOT_PUBLISHED).",
                example = "PUBLISHED")
        String statusPublicacao,

        @Schema(description = "TLDs alternativos sugeridos quando o domínio principal está ocupado.")
        List<String> sugestoes,

        @Schema(description = "Provedor que respondeu com sucesso (rastreabilidade de SLA).",
                example = "Registro.br")
        String provedor
) {
}
