package br.com.cernebr.gateway_nacional.insights.trabalhista.dto;

import java.math.BigDecimal;

public record RemuneracaoMediaDTO(
    String codigoIbge,
    String codigoCbo,
    BigDecimal remuneracaoMedia,
    BigDecimal tetoSalarial,
    BigDecimal pisoSalarial,
    String fonte,
    String periodoReferencia
) {}
