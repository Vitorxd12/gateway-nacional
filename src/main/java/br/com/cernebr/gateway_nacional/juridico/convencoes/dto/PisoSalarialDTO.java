package br.com.cernebr.gateway_nacional.juridico.convencoes.dto;

import java.math.BigDecimal;

public record PisoSalarialDTO(
    String codigoIbge,
    String codigoCbo,
    BigDecimal pisoVigente,
    String sindicatoPatronal,
    String sindicatoLaboral,
    String validade,
    String fonte
) {}
