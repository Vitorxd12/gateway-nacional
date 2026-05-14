package br.com.cernebr.gateway_nacional.veicular.historico.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Risk band that summarises the union of leilão and sinistro indicators
 * collected by the historico orchestrator.
 *
 * <p>Calculation rule:
 * <ul>
 *   <li>{@link #BAIXO} — neither indicator fired (nada consta);</li>
 *   <li>{@link #MEDIO} — exactly one indicator fired (leilão XOR sinistro);</li>
 *   <li>{@link #ALTO}  — both indicators fired.</li>
 * </ul>
 *
 * <p>The enum is stable and travels in the response — callers may switch on
 * it without re-deriving from boolean flags.</p>
 */
@Schema(
        name = "RiscoConsolidado",
        description = "Banda de risco consolidada do veículo. BAIXO = nada consta; MEDIO = um indício; ALTO = leilão E sinistro."
)
public enum RiscoConsolidado {
    BAIXO,
    MEDIO,
    ALTO;

    public static RiscoConsolidado from(boolean indicioLeilao, boolean indicioSinistro) {
        if (indicioLeilao && indicioSinistro) return ALTO;
        if (indicioLeilao || indicioSinistro) return MEDIO;
        return BAIXO;
    }
}
