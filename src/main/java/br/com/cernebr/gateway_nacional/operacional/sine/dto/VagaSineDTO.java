package br.com.cernebr.gateway_nacional.operacional.sine.dto;

public record VagaSineDTO(
    String codigoIbge,
    String codigoCbo,
    Integer vagasDisponiveis,
    String fonte,
    String ultimaAtualizacao
) {}
