package br.com.cernebr.gateway_nacional.cadastral.ibge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope interno do cache Redis para a lista de UFs. Mesmo motivo do
 * {@link MunicipiosEnvelope} — {@code CachedEntry} não cacheia {@code List<>}
 * cru. Hidden no OpenAPI; o controller desembrulha.
 */
@Schema(name = "UfsEnvelope", hidden = true)
public record UfsEnvelope(List<UfResponse> ufs) {
}
