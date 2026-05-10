package br.com.cernebr.gateway_nacional.financeiro.b3.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de fundos listados aceitos pela B3 no endpoint
 * {@code /fundsListedProxy/Search/GetListFunds}. Espelha exatamente o
 * conjunto que a BrasilAPI valida em {@code pages/api/tickers/b3/fundos/v1/[typeFund].js}.
 *
 * <p>Os enum names usam {@code _} no lugar de {@code -} (limitação Java);
 * o {@link #wireValue} preserva o formato exato exigido pela B3
 * ({@code FIAGRO-FII}, com hífen).</p>
 */
@Schema(description = "Tipos de fundos B3 suportados")
public enum B3FundoTipo {
    FII("FII"),
    SETORIAL("SETORIAL"),
    FIAGRO_FII("FIAGRO-FII"),
    FIAGRO_FIDC("FIAGRO-FIDC"),
    FIAGRO_FIP("FIAGRO-FIP"),
    FIP("FIP"),
    FIA("FIA");

    private final String wireValue;

    B3FundoTipo(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /**
     * Resolve o tipo a partir do path-param do controller. Aceita tanto a
     * forma com hífen ({@code FIAGRO-FII}) quanto com underscore
     * ({@code FIAGRO_FII}). Case-insensitive.
     */
    public static Optional<B3FundoTipo> fromWireValue(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toUpperCase().replace('_', '-');
        return Arrays.stream(values())
                .filter(t -> t.wireValue.equals(normalized))
                .findFirst();
    }
}
