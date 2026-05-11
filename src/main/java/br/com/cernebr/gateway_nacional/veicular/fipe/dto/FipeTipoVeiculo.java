package br.com.cernebr.gateway_nacional.veicular.fipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de veículo aceitos pela FIPE-Oficial. Espelha o mapa da BrasilAPI
 * em {@code services/fipe/constants.js} (carros=1, motos=2, caminhoes=3).
 *
 * <p>O {@link #wireValue} é o nome usado no path-param público
 * (compatível com BrasilAPI: {@code carros}, {@code motos}, {@code caminhoes}).
 * O {@link #fipeCodigoVeiculo} é o int que vai no form-encoded
 * {@code codigoTipoVeiculo} dos endpoints {@code ConsultarMarcas} /
 * {@code ConsultarModelos} da FIPE-Oficial.</p>
 */
@Schema(description = "Tipos de veículo FIPE")
public enum FipeTipoVeiculo {
    CARROS("carros", 1),
    MOTOS("motos", 2),
    CAMINHOES("caminhoes", 3);

    private final String wireValue;
    private final int fipeCodigoVeiculo;

    FipeTipoVeiculo(String wireValue, int fipeCodigoVeiculo) {
        this.wireValue = wireValue;
        this.fipeCodigoVeiculo = fipeCodigoVeiculo;
    }

    public String wireValue() {
        return wireValue;
    }

    public int fipeCodigoVeiculo() {
        return fipeCodigoVeiculo;
    }

    public static Optional<FipeTipoVeiculo> fromWireValue(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(t -> t.wireValue.equals(normalized))
                .findFirst();
    }
}
