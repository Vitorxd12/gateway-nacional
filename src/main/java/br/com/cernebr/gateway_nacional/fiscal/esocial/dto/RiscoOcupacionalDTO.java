package br.com.cernebr.gateway_nacional.fiscal.esocial.dto;

public record RiscoOcupacionalDTO(
    String codigoIbge,
    String codigoCbo,
    String grauRiscoRat,
    String fatorAcidentarioFap,
    String periculosidade,
    String fonte
) {}
