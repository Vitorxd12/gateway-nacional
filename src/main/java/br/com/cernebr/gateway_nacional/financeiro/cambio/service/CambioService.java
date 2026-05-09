package br.com.cernebr.gateway_nacional.financeiro.cambio.service;

import br.com.cernebr.gateway_nacional.financeiro.cambio.client.CambioClient;
import br.com.cernebr.gateway_nacional.financeiro.cambio.dto.CambioEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Orquestra a consulta de cotações via AwesomeAPI com cache de curta duração.
 *
 * <p>A chave do cache é a lista de pares <b>normalizada</b> (uppercase + sem
 * espaços + ordem alfabética estável), de modo que {@code "USD-BRL,EUR-BRL"} e
 * {@code "eur-brl, usd-brl"} compartilhem a mesma entrada Redis e maximizem
 * a taxa de hit em padrões de uso de dashboards.</p>
 */
@Slf4j
@Service
public class CambioService {

    private static final String DOMAIN = "cambio";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final CambioClient cambioClient;
    private final MeterRegistry meterRegistry;

    public CambioService(CambioClient cambioClient, MeterRegistry meterRegistry) {
        this.cambioClient = cambioClient;
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "cambio", key = "T(br.com.cernebr.gateway_nacional.financeiro.cambio.service.CambioService).normalizar(#pares)")
    public CambioEnvelope consultar(String pares) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CambioEnvelope envelope = new CambioEnvelope(cambioClient.fetch(pares));
            recordOutcome(cambioClient.providerName(), "success", sample);
            log.info("Câmbio resolvido upstream pares={} resultados={}", pares, envelope.cotacoes().size());
            return envelope;
        } catch (RuntimeException ex) {
            recordOutcome(cambioClient.providerName(), "failure", sample);
            log.warn("Provider {} falhou para câmbio pares={} ({}).",
                    cambioClient.providerName(), pares, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Normaliza o argumento de pares para uma chave de cache estável:
     * uppercase, sem espaços, ordenado alfabeticamente. Pública (e estática)
     * porque o SpEL do {@link Cacheable} resolve a chave por reflexão.
     */
    public static String normalizar(String pares) {
        if (pares == null || pares.isBlank()) {
            return "";
        }
        return Arrays.stream(pares.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private void recordOutcome(String providerName, String outcome, Timer.Sample sample) {
        String providerTag = providerName.toLowerCase(Locale.ROOT);
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", providerTag)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", providerTag,
                "outcome", outcome).increment();
    }
}
