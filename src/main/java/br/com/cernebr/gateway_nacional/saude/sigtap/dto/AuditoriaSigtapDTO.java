package br.com.cernebr.gateway_nacional.saude.sigtap.dto;

public record AuditoriaSigtapDTO(
    String codigoIbge,
    String codigoCbo,
    String codigoSigtap,
    Boolean compativel,
    String justificativa,
    String fonte
) {}
