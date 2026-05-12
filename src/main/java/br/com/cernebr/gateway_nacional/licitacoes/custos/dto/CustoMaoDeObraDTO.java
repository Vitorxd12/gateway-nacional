package br.com.cernebr.gateway_nacional.licitacoes.custos.dto;

import java.math.BigDecimal;

public record CustoMaoDeObraDTO(
    String codigoIbge,
    String codigoCbo,
    BigDecimal valorHomemHoraMedio,
    BigDecimal valorHomemHoraMaximo,
    String dataReferencia,
    String fonte
) {}
