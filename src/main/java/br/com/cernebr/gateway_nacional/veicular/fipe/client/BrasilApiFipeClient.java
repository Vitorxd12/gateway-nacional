package br.com.cernebr.gateway_nacional.veicular.fipe.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeMarcaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipePrecoResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTabelaReferenciaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTipoVeiculo;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeVeiculoResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Primary FIPE provider — BrasilAPI (https://brasilapi.com.br).
 *
 * <p>BrasilAPI's endpoint {@code /api/fipe/preco/v1/{codigoFipe}} returns
 * <em>all</em> available year-fuel combinations for a given FIPE code as an
 * array. The Anti-Corruption Layer filters by {@code anoModelo} client-side
 * and converts the Brazilian-formatted price string ({@code "R$ 80.444,00"})
 * into a {@link BigDecimal}.</p>
 */
@Slf4j
@Component
public class BrasilApiFipeClient implements FipeClientProvider, FipeNavegacaoProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-Fipe";

    private static final ParameterizedTypeReference<List<BrasilApiFipePayload>> PAYLOAD_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<BrasilApiMarcaPayload>> MARCAS_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<BrasilApiVeiculoPayload>> VEICULOS_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<BrasilApiTabelaPayload>> TABELAS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BrasilApiFipeClient(RestClient.Builder builder,
                               @Value("${gateway.fipe.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "brasilApiFipeCB", fallbackMethod = "fallback")
    public FipePrecoResponse fetchPreco(String codigoFipe, String anoModelo) {
        List<BrasilApiFipePayload> payload = restClient.get()
                .uri("/api/fipe/preco/v1/{codigoFipe}", codigoFipe)
                .retrieve()
                .body(PAYLOAD_LIST_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia para o código FIPE informado.");
        }

        int targetYear = parseAnoModelo(anoModelo);
        return payload.stream()
                .filter(item -> item.anoModelo() == targetYear)
                .findFirst()
                .map(BrasilApiFipePayload::toFipePrecoResponse)
                .orElseThrow(() -> new ResourceUnavailableException(PROVIDER_NAME,
                        "Ano modelo " + anoModelo + " não disponível no catálogo BrasilAPI para este código."));
    }

    /**
     * Retorna TODOS os registros de preço para um código FIPE (todos os anos e
     * tipos de combustível) sem filtragem por {@code anoModelo}.
     *
     * <p>Usado pelo endpoint de histórico:
     * {@code GET /api/v1/fipe/preco/historico/{fipeCode}}.</p>
     */
    @CircuitBreaker(name = "brasilApiFipeCB", fallbackMethod = "fallbackTodosPrecos")
    public List<FipePrecoResponse> fetchTodosPrecos(String codigoFipe) {
        List<BrasilApiFipePayload> payload = restClient.get()
                .uri("/api/fipe/preco/v1/{codigoFipe}", codigoFipe)
                .retrieve()
                .body(PAYLOAD_LIST_TYPE);

        if (payload == null || payload.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia para o código FIPE (histórico).");
        }
        return payload.stream().map(BrasilApiFipePayload::toFipePrecoResponse).toList();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private FipePrecoResponse fallback(String codigoFipe, String anoModelo, Throwable cause) {
        log.warn("BrasilAPI (FIPE) fallback triggered for codigoFipe={} anoModelo={} cause={}",
                codigoFipe, anoModelo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private List<FipePrecoResponse> fallbackTodosPrecos(String codigoFipe, Throwable cause) {
        log.warn("BrasilAPI (FIPE) fallback histórico for codigoFipe={} cause={}", codigoFipe, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto (histórico).", cause);
    }

    private static int parseAnoModelo(String anoModelo) {
        try {
            return Integer.parseInt(anoModelo);
        } catch (NumberFormatException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Ano modelo inválido: " + anoModelo, ex);
        }
    }

    /**
     * Strips Brazilian currency formatting ({@code "R$ 80.444,00"}) into a
     * {@link BigDecimal}. {@code .} is the thousand separator; {@code ,} is
     * the decimal separator.
     */
    private static BigDecimal parseBRCurrency(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            return null;
        }
        String cleaned = formatted
                .replace("R$", "")
                .replace(".", "")
                .replace(",", ".")
                .trim();
        return new BigDecimal(cleaned);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navegação FIPE (FipeNavegacaoProvider) — proxy às rotas /api/fipe/*/v1
    // da BrasilAPI. Histórica observação: a BrasilAPI pode retornar 500 com
    // AxiosError 403 quando o upstream FIPE bloqueia o proxy server-side; o
    // CB próprio absorve essas falhas e o CambioService cascateia pro scraper.
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @CircuitBreaker(name = "brasilApiFipeCB", fallbackMethod = "fallbackMarcas")
    public List<FipeMarcaResponse> listMarcas(FipeTipoVeiculo tipo, @Nullable Integer tabelaReferencia) {
        List<BrasilApiMarcaPayload> raw = restClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/fipe/marcas/v1/{tipo}");
                    if (tabelaReferencia != null) b.queryParam("tabela_referencia", tabelaReferencia);
                    return b.build(tipo.wireValue());
                })
                .retrieve()
                .body(MARCAS_TYPE);

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia de marcas para tipo=" + tipo);
        }
        return raw.stream().map(p -> new FipeMarcaResponse(p.nome(), p.valor())).toList();
    }

    @Override
    @CircuitBreaker(name = "brasilApiFipeCB", fallbackMethod = "fallbackVeiculos")
    public List<FipeVeiculoResponse> listVeiculosByMarca(FipeTipoVeiculo tipo,
                                                        String codigoMarca,
                                                        @Nullable Integer tabelaReferencia) {
        List<BrasilApiVeiculoPayload> raw = restClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/fipe/veiculos/v1/{tipo}/{codigoMarca}");
                    if (tabelaReferencia != null) b.queryParam("tabela_referencia", tabelaReferencia);
                    return b.build(tipo.wireValue(), codigoMarca);
                })
                .retrieve()
                .body(VEICULOS_TYPE);

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia de modelos para tipo=" + tipo + " marca=" + codigoMarca);
        }
        // A BrasilAPI dropa o Value original — devolvemos só o modelo.
        // O FipeVeiculoResponse.valor fica null e some do JSON via @JsonInclude(NON_NULL).
        return raw.stream().map(p -> new FipeVeiculoResponse(p.modelo(), null)).toList();
    }

    @Override
    @CircuitBreaker(name = "brasilApiFipeCB", fallbackMethod = "fallbackTabelas")
    public List<FipeTabelaReferenciaResponse> listTabelasReferencia() {
        List<BrasilApiTabelaPayload> raw = restClient.get()
                .uri("/api/fipe/tabelas/v1")
                .retrieve()
                .body(TABELAS_TYPE);

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia de tabelas-de-referência.");
        }
        return raw.stream()
                .map(p -> new FipeTabelaReferenciaResponse(p.codigo(), p.mes()))
                .toList();
    }

    @SuppressWarnings("unused")
    private List<FipeMarcaResponse> fallbackMarcas(FipeTipoVeiculo tipo, Integer tabelaReferencia, Throwable cause) {
        log.warn("BrasilAPI (FIPE) fallback marcas tipo={} tabela={} cause={}", tipo, tabelaReferencia, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto (marcas).", cause);
    }

    @SuppressWarnings("unused")
    private List<FipeVeiculoResponse> fallbackVeiculos(FipeTipoVeiculo tipo, String codigoMarca,
                                                      Integer tabelaReferencia, Throwable cause) {
        log.warn("BrasilAPI (FIPE) fallback veiculos tipo={} marca={} tabela={} cause={}",
                tipo, codigoMarca, tabelaReferencia, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto (veiculos).", cause);
    }

    @SuppressWarnings("unused")
    private List<FipeTabelaReferenciaResponse> fallbackTabelas(Throwable cause) {
        log.warn("BrasilAPI (FIPE) fallback tabelas cause={}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto (tabelas).", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiMarcaPayload(String nome, String valor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiVeiculoPayload(String modelo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiTabelaPayload(int codigo, String mes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiFipePayload(
            String valor,
            String marca,
            String modelo,
            int anoModelo,
            String combustivel,
            String codigoFipe,
            String mesReferencia
    ) {
        FipePrecoResponse toFipePrecoResponse() {
            return new FipePrecoResponse(
                    codigoFipe,
                    marca,
                    modelo,
                    anoModelo,
                    combustivel,
                    parseBRCurrency(valor),
                    mesReferencia
            );
        }
    }
}
