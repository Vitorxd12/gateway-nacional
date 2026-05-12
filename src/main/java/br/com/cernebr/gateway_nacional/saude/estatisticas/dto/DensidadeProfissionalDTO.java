package br.com.cernebr.gateway_nacional.saude.estatisticas.dto;

public record DensidadeProfissionalDTO(
    String codigoIbge,
    String codigoCbo,
    Integer quantidadeAtivos,
    Double profissionaisPorMilHabitantes,
    String fonte
) {}
