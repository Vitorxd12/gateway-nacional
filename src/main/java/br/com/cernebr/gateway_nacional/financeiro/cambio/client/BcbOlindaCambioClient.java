package br.com.cernebr.gateway_nacional.financeiro.cambio.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provider PTAX direto via API OLINDA do Banco Central do Brasil.
 *
 * <p>Endpoint:
 * {@code https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata/CotacaoMoedaDia(moeda=@moeda,dataCotacao=@dataCotacao)?@moeda='USD'&@dataCotacao='MM-DD-YYYY'&$top=100&$format=json}.</p>
 *
 * <p>Particularidade: o BCB exige formato de data {@code MM-DD-YYYY} (US-style),
 * não ISO. E retorna lista vazia ({@code value: []}) quando não há publicação
 * naquela data — fim de semana, feriado bancário, ou hoje antes da publicação
 * matinal. Implementamos retry retroativo dia-a-dia até
 * {@code gateway.cambio.bcb.max-fallback-days} (default 7) — cobre o caso pior
 * de Carnaval (5 dias úteis pulados) + fim de semana adjacente.</p>
 *
 * <p>Multi-pair: PTAX OLINDA atende uma moeda por chamada. Disparamos todas
 * em paralelo via virtual threads, mas <b>compartilhando a data de referência
 * resolvida</b> — primeiro descobrimos a última data com publicação, depois
 * batemos cada moeda nessa mesma data. Garante consistência (todas as
 * cotações na mesma janela temporal) e evita 7×N tentativas de retry.</p>
 */
@Slf4j
@Component
public class BcbOlindaCambioClient implements CambioPtaxClientProvider {

    public static final String PROVIDER_NAME = "BCB-Olinda-PTAX";
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BCB_DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");
    private static final String COTACAO_PATH =
            "/olinda/servico/PTAX/versao/v1/odata/CotacaoMoedaDia(moeda=@moeda,dataCotacao=@dataCotacao)";

    private final RestClient restClient;
    private final BcbMoedasCatalogService catalogService;
    private final int maxFallbackDays;
    private final ExecutorService perPairExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BcbOlindaCambioClient(RestClient.Builder builder,
                                 @Value("${gateway.cambio.bcb.base-url:https://olinda.bcb.gov.br}") String baseUrl,
                                 @Value("${gateway.cambio.bcb.max-fallback-days:7}") int maxFallbackDays,
                                 BcbMoedasCatalogService catalogService) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.maxFallbackDays = maxFallbackDays;
        this.catalogService = catalogService;
    }

    @Override
    @CircuitBreaker(name = "cambioBcbOlindaCB", fallbackMethod = "fallback")
    public List<CambioResponse> fetchPtax(String pares) {
        List<CambioPair> pairs = CambioPair.parseAll(pares, catalogService.supportedCurrencies());

        // Primeiro par: descobre a data de referência válida (com retry).
        // A primeira moeda do BCB com PTAX naquela data já garante que a data
        // tem publicação — moedas raras (TRY, ZAR) podem ter publicação
        // intermitente, mas USD/EUR são publicados todo dia útil.
        CambioPair probePair = pairs.get(0);
        ResolvedQuote probe = resolveWithRetry(probePair, LocalDate.now(BR_ZONE));

        // Demais pares: na mesma data resolvida pelo probe.
        List<CompletableFuture<CambioResponse>> futures = pairs.stream()
                .skip(1)
                .map(pair -> CompletableFuture.supplyAsync(
                        () -> {
                            BcbBoletim boletim = fetchSingle(pair, probe.date());
                            return toResponse(pair, boletim);
                        },
                        perPairExecutor))
                .toList();

        CambioResponse[] results = new CambioResponse[pairs.size()];
        results[0] = toResponse(probePair, probe.boletim());
        for (int i = 1; i < pairs.size(); i++) {
            try {
                results[i] = futures.get(i - 1).join();
            } catch (RuntimeException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof RuntimeException re) throw re;
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "Falha ao consolidar PTAX multi-par via BCB OLINDA: " + cause.getMessage(), cause);
            }
        }
        return List.of(results);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<CambioResponse> fallback(String pares, Throwable cause) {
        log.warn("BCB OLINDA fallback triggered for pares={} cause={}", pares, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BCB OLINDA indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Para o par-sonda, retrocede até {@code maxFallbackDays} dias para encontrar
     * a publicação mais recente. Demais pares reusam a data resolvida.
     */
    private ResolvedQuote resolveWithRetry(CambioPair pair, LocalDate today) {
        Throwable lastError = null;
        for (int offset = 1; offset <= maxFallbackDays; offset++) {
            LocalDate attempt = today.minusDays(offset);
            try {
                BcbBoletim boletim = fetchSingle(pair, attempt);
                if (boletim != null) {
                    log.debug("BCB OLINDA PTAX resolved for {} on {} (offset={} day(s))",
                            pair.moedaOrigem(), attempt, offset);
                    return new ResolvedQuote(attempt, boletim);
                }
            } catch (RuntimeException ex) {
                log.debug("BCB OLINDA empty/failed for {} on {}: {}",
                        pair.moedaOrigem(), attempt, ex.getMessage());
                lastError = ex;
            }
        }
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Sem PTAX publicado para " + pair.moedaOrigem() + " em " + maxFallbackDays + " dias retroativos a partir de " + today,
                lastError);
    }

    private BcbBoletim fetchSingle(CambioPair pair, LocalDate date) {
        String dateUs = date.format(BCB_DATE);
        // OLINDA usa parâmetros nomeados ODATA com aspas simples — escapamos via
        // queryParam e o RestClient cuida do percent-encoding.
        BcbResponse payload;
        try {
            payload = restClient.get()
                    .uri(uri -> uri.path(COTACAO_PATH)
                            .queryParam("@moeda", "'" + pair.moedaOrigem() + "'")
                            .queryParam("@dataCotacao", "'" + dateUs + "'")
                            .queryParam("$top", 100)
                            .queryParam("$format", "json")
                            .build())
                    .retrieve()
                    .body(BcbResponse.class);
        } catch (HttpClientErrorException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BCB OLINDA HTTP " + ex.getStatusCode().value() + " para " + pair.moedaOrigem() + "/" + date,
                    ex);
        }

        if (payload == null || payload.value() == null || payload.value().isEmpty()) {
            return null;
        }
        // Prefere "Fechamento PTAX" (oficial); senão pega o último boletim do dia.
        return payload.value().stream()
                .filter(b -> b.tipoBoletim() != null && b.tipoBoletim().toUpperCase().contains("FECHAMENTO"))
                .findFirst()
                .orElse(payload.value().get(payload.value().size() - 1));
    }

    private static CambioResponse toResponse(CambioPair pair, BcbBoletim boletim) {
        return new CambioResponse(
                pair.moedaOrigem(),
                pair.moedaDestino(),
                boletim.cotacaoCompra(),
                boletim.cotacaoVenda(),
                null,
                parseDataHora(boletim.dataHoraCotacao())
        );
    }

    private static LocalDateTime parseDataHora(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"));
        } catch (Exception ex) {
            return null;
        }
    }

    private record ResolvedQuote(LocalDate date, BcbBoletim boletim) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BcbResponse(List<BcbBoletim> value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BcbBoletim(
            BigDecimal paridadeCompra,
            BigDecimal paridadeVenda,
            BigDecimal cotacaoCompra,
            BigDecimal cotacaoVenda,
            String dataHoraCotacao,
            String tipoBoletim
    ) {
    }
}
