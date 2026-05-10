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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provider PTAX via BrasilAPI ({@code GET /api/cambio/v1/cotacao/{moeda}/{data}}).
 *
 * <p>A BrasilAPI consolida internamente a cascata de retry retroativo do BCB
 * OLINDA (até 7 dias úteis) e devolve a lista de boletins do dia. Por estarmos
 * em hedge com {@link BcbOlindaCambioClient}, mantemos a chamada simples aqui
 * (uma única data: {@code D-1}) — se ela falhar ou vier vazia, deixamos o
 * BCB direto resolver com sua própria lógica retroativa.</p>
 *
 * <p><b>Multi-pair:</b> a BrasilAPI atende uma moeda por chamada. Disparamos
 * todas as moedas em paralelo via virtual threads — custo desprezível e mantém
 * latência total ≈ {@code max(latência por moeda)}.</p>
 */
@Slf4j
@Component
public class BrasilApiCambioClient implements CambioPtaxClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-PTAX";
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final RestClient restClient;
    private final BcbMoedasCatalogService catalogService;
    private final ExecutorService perPairExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BrasilApiCambioClient(RestClient.Builder builder,
                                 @Value("${gateway.cambio.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl,
                                 BcbMoedasCatalogService catalogService) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.catalogService = catalogService;
    }

    @Override
    @CircuitBreaker(name = "cambioBrasilApiCB", fallbackMethod = "fallback")
    public List<CambioResponse> fetchPtax(String pares) {
        List<CambioPair> pairs = CambioPair.parseAll(pares, catalogService.supportedCurrencies());
        LocalDate referenceDate = lastBusinessDay(LocalDate.now(BR_ZONE));

        List<CompletableFuture<CambioResponse>> futures = pairs.stream()
                .map(pair -> CompletableFuture.supplyAsync(
                        () -> fetchSinglePair(pair, referenceDate), perPairExecutor))
                .toList();

        try {
            return futures.stream().map(CompletableFuture::join).toList();
        } catch (RuntimeException ex) {
            // Qualquer falha em qualquer par (não-elegível, 404 do upstream,
            // erro de rede) propaga e leva o CambioService a cascatear para
            // o AwesomeAPI — comportamento documentado em CambioPtaxClientProvider.
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException re) throw re;
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao consolidar PTAX multi-par via BrasilAPI: " + cause.getMessage(), cause);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private List<CambioResponse> fallback(String pares, Throwable cause) {
        log.warn("BrasilAPI PTAX fallback triggered for pares={} cause={}", pares, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI PTAX indisponível ou Circuit Breaker aberto.", cause);
    }

    private CambioResponse fetchSinglePair(CambioPair pair, LocalDate date) {
        BrasilApiCambioPayload payload;
        try {
            payload = restClient.get()
                    .uri("/api/cambio/v1/cotacao/{moeda}/{data}",
                            pair.moedaOrigem(), date.format(ISO_DATE))
                    .retrieve()
                    .body(BrasilApiCambioPayload.class);
        } catch (HttpClientErrorException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI PTAX devolveu HTTP " + ex.getStatusCode().value() + " para " + pair.moedaOrigem(),
                    ex);
        }

        if (payload == null || payload.cotacoes() == null || payload.cotacoes().isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI PTAX sem boletins para moeda=" + pair.moedaOrigem() + " data=" + date);
        }

        // Prefere o "Fechamento PTAX" (oficial); senão pega o último boletim do dia.
        BrasilApiPtaxBoletim chosen = payload.cotacoes().stream()
                .filter(b -> b.tipoBoletim() != null && b.tipoBoletim().toUpperCase().contains("FECHAMENTO"))
                .findFirst()
                .orElse(payload.cotacoes().get(payload.cotacoes().size() - 1));

        return new CambioResponse(
                pair.moedaOrigem(),
                pair.moedaDestino(),
                chosen.cotacaoCompra(),
                chosen.cotacaoVenda(),
                null,
                parseDataHora(chosen.dataHoraCotacao())
        );
    }

    /**
     * Retrocede do dia atual até o último dia útil bancário (segunda a sexta).
     * Não consulta calendário de feriados — se o BCB não publicou (feriado),
     * a chamada falhará e o {@link BcbOlindaCambioClient} (que tem retry
     * dia-a-dia até 7) resolve via hedge.
     */
    private static LocalDate lastBusinessDay(LocalDate today) {
        LocalDate d = today.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static LocalDateTime parseDataHora(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // BrasilAPI propaga o formato do BCB: "2026-05-09 13:11:01.137"
        try {
            return LocalDateTime.parse(raw,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"));
        } catch (Exception ex) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiCambioPayload(List<BrasilApiPtaxBoletim> cotacoes) {
        @Override
        public List<BrasilApiPtaxBoletim> cotacoes() {
            return cotacoes == null ? new ArrayList<>() : cotacoes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiPtaxBoletim(
            BigDecimal paridade_compra,
            BigDecimal paridade_venda,
            BigDecimal cotacao_compra,
            BigDecimal cotacao_venda,
            String data_hora_cotacao,
            String tipo_boletim
    ) {
        BigDecimal cotacaoCompra() { return cotacao_compra; }
        BigDecimal cotacaoVenda() { return cotacao_venda; }
        String dataHoraCotacao() { return data_hora_cotacao; }
        String tipoBoletim() { return tipo_boletim; }
    }
}
