package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Catálogo dinâmico das moedas publicadas pelo BCB no PTAX, consumido do
 * endpoint OData {@code /odata/Moedas}.
 *
 * <p>Substitui o conjunto hardcoded que existia em {@link CambioPair}: o BCB
 * adiciona/retira moedas com baixa frequência (~uma alteração por ano), mas
 * uma lista estática estava sujeita a desync silencioso. O endpoint
 * {@code /Moedas} é o ponto canônico — mesma fonte que a BrasilAPI consulta
 * em {@code services/cambio/moedas.js}.</p>
 *
 * <h2>Cache</h2>
 * <p>{@code @Cacheable} em {@code ptaxCatalog} (hard-TTL 30d). Como o
 * resultado é um {@code Set<String>} de cardinalidade ~16, o custo de
 * armazenamento é desprezível e a janela longa absorve qualquer instabilidade
 * intermitente do BCB sem degradar o caminho crítico do {@link CambioService}.</p>
 *
 * <h2>Fallback embutido</h2>
 * <p>Se a primeira chamada (cache miss) ao BCB falhar, devolvemos
 * {@link #FALLBACK_CATALOG} — a lista que estava hardcoded até agora.
 * Spring cacheia esse fallback como qualquer outro retorno; a próxima
 * tentativa real ao BCB só acontece após o hard-TTL. Compromisso aceitável:
 * 30d de degradação leve (catálogo congelado) &gt; loop de tentativas em
 * todas as requests de câmbio.</p>
 */
@Slf4j
@Component
public class BcbMoedasCatalogService {

    private static final String CATALOG_PATH = "/olinda/servico/PTAX/versao/v1/odata/Moedas";

    /**
     * Lista de fallback usada quando o BCB {@code /Moedas} está indisponível
     * na primeira chamada após o hard-TTL expirar. Sincronizada com o catálogo
     * publicado em maio/2026.
     */
    private static final Set<String> FALLBACK_CATALOG = Set.of(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "DKK",
            "NOK", "SEK", "ARS", "MXN", "TRY", "ZAR", "CNY", "HKD"
    );

    private final RestClient restClient;

    public BcbMoedasCatalogService(RestClient.Builder builder,
                                   @Value("${gateway.cambio.bcb.base-url:https://olinda.bcb.gov.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Cacheable(cacheNames = "ptaxCatalog", key = "'all'")
    @CircuitBreaker(name = "cambioMoedasCatalogCB", fallbackMethod = "fallback")
    public Set<String> supportedCurrencies() {
        log.debug("BCB /Moedas fetch — catalog cache miss, refreshing");
        BcbMoedasResponse payload = restClient.get()
                .uri(uri -> uri.path(CATALOG_PATH)
                        .queryParam("$top", 100)
                        .queryParam("$format", "json")
                        .build())
                .retrieve()
                .body(BcbMoedasResponse.class);

        if (payload == null || payload.value() == null || payload.value().isEmpty()) {
            log.warn("BCB /Moedas devolveu corpo vazio — degradando para catálogo fallback");
            return FALLBACK_CATALOG;
        }
        Set<String> simbolos = new HashSet<>(payload.value().size());
        for (BcbMoeda moeda : payload.value()) {
            if (moeda.simbolo() != null && !moeda.simbolo().isBlank()) {
                simbolos.add(moeda.simbolo());
            }
        }
        log.info("BCB /Moedas atualizado: {} moedas no catálogo PTAX", simbolos.size());
        return simbolos;
    }

    @SuppressWarnings("unused")
    private Set<String> fallback(Throwable cause) {
        log.warn("BCB /Moedas fallback acionado, usando catálogo embutido: {}", cause.toString());
        return FALLBACK_CATALOG;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BcbMoedasResponse(List<BcbMoeda> value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BcbMoeda(String simbolo, String nomeFormatado, String tipoMoeda) {
    }
}
