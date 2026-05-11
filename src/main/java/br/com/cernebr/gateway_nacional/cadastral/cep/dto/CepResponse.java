package br.com.cernebr.gateway_nacional.cadastral.cep.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Unified address payload exposed by the Gateway, regardless of which upstream
 * provider (ViaCEP, BrasilAPI, AwesomeAPI) actually resolved the CEP.
 *
 * <p><b>Compatibilidade v1:</b> o campo {@code localizacao} é uma adição
 * opcional ao contrato — clientes existentes que ignoram campos desconhecidos
 * continuam funcionando sem alteração; clientes novos podem optar por
 * consumi-lo. Por isso o endpoint segue sob {@code /api/v1/cep/{cep}}, sem
 * promoção de versão.</p>
 */
@Schema(name = "CepResponse",
        description = "Endereço resolvido a partir do CEP, em formato unificado pelo Gateway Nacional. Inclui geocodificação (lat/long) quando disponível.")
public record CepResponse(
        @Schema(description = "CEP consultado", example = "01001-000")
        String cep,

        @Schema(description = "Logradouro (rua, avenida, etc.)", example = "Praça da Sé")
        String logradouro,

        @Schema(description = "Complemento adicional do endereço", example = "lado ímpar")
        String complemento,

        @Schema(description = "Bairro", example = "Sé")
        String bairro,

        @Schema(description = "Município", example = "São Paulo")
        String localidade,

        @Schema(description = "Sigla da Unidade Federativa", example = "SP")
        String uf,

        @Schema(description = "Código IBGE do município", example = "3550308")
        String ibge,

        @Schema(description = "Geocodificação do endereço — null quando nenhuma fonte de geo respondeu.",
                nullable = true)
        Localizacao localizacao
) {

    /**
     * Construtor de conveniência que preserva o contrato pré-geo: para uso
     * interno por providers que ainda não capturam lat/long. O campo {@code localizacao}
     * fica {@code null} e o {@code GeoEnrichmentService} preenche depois.
     */
    public CepResponse(String cep, String logradouro, String complemento,
                       String bairro, String localidade, String uf, String ibge) {
        this(cep, logradouro, complemento, bairro, localidade, uf, ibge, null);
    }

    /**
     * Retorna uma cópia da resposta com o campo {@code ibge} substituído.
     * Usado pelo {@link br.com.cernebr.gateway_nacional.cadastral.cep.service.IbgeEnrichmentService}
     * para back-fill sem perder a localização eventualmente já preenchida.
     */
    public CepResponse withIbge(String ibgeNovo) {
        return new CepResponse(cep, logradouro, complemento, bairro, localidade, uf, ibgeNovo, localizacao);
    }

    /**
     * Retorna uma cópia da resposta com a localização preenchida. Imutável —
     * o {@link br.com.cernebr.gateway_nacional.cadastral.cep.service.GeoEnrichmentService}
     * gera um novo registro e descarta o antigo.
     */
    public CepResponse withLocalizacao(Localizacao loc) {
        return new CepResponse(cep, logradouro, complemento, bairro, localidade, uf, ibge, loc);
    }

    @Schema(name = "CepResponse.Localizacao",
            description = "Coordenadas geográficas WGS84 do endereço, com indicação da precisão e fonte.")
    public record Localizacao(
            @Schema(description = "Latitude em graus decimais WGS84", example = "-23.5505")
            BigDecimal latitude,

            @Schema(description = "Longitude em graus decimais WGS84", example = "-46.6333")
            BigDecimal longitude,

            @Schema(description = "Indicação de precisão: EXATA (postcode bateu 8 dígitos) ou APROXIMADA (5 dígitos / centroide do bairro)",
                    example = "EXATA",
                    allowableValues = {"EXATA", "APROXIMADA"})
            String precisao,

            @Schema(description = "Fonte que resolveu a coordenada", example = "OpenStreetMap-Nominatim")
            String fonte
    ) {
    }
}
