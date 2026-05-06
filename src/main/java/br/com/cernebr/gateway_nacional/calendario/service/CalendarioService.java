package br.com.cernebr.gateway_nacional.calendario.service;

import br.com.cernebr.gateway_nacional.calendario.client.BrasilApiFeriadoClient;
import br.com.cernebr.gateway_nacional.calendario.client.FeriadoClientProvider;
import br.com.cernebr.gateway_nacional.calendario.client.NagerDateFeriadoClient;
import br.com.cernebr.gateway_nacional.calendario.dto.FeriadoResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Orchestrates the cascade for Brazilian holiday lookup.
 * Order: BrasilAPI → Nager.Date → in-memory deterministic calculator.
 *
 * <p>Because the calculator is the last provider in the chain and never
 * fails, this service never throws {@link ResourceUnavailableException} for
 * the holiday lookup itself — the gateway always answers. The exception
 * exists in the signature only for unforeseen pathological inputs.</p>
 *
 * <p>TODO: Implementar suporte a feriados estaduais e municipais passando a UF
 * ou código IBGE. Provedor primário (BrasilAPI) suporta, mas exige refatoração
 * da assinatura do endpoint para receber localização.</p>
 */
@Slf4j
@Service
public class CalendarioService {

    private static final String DOMAIN = "calendario";
    private static final String AGGREGATE_PROVIDER = "all-providers";

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final List<FeriadoClientProvider> providersInOrder;
    private final MeterRegistry meterRegistry;

    public CalendarioService(BrasilApiFeriadoClient primary,
                             NagerDateFeriadoClient secondary,
                             FeriadoNacionalCalculadorService offlineCalculator,
                             MeterRegistry meterRegistry) {
        this.providersInOrder = List.of(primary, secondary, offlineCalculator);
        this.meterRegistry = meterRegistry;
    }

    @Cacheable(cacheNames = "feriados", key = "#ano")
    public List<FeriadoResponse> findByAno(int ano) {
        for (FeriadoClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                List<FeriadoResponse> feriados = provider.fetch(ano);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("Holidays for ano={} resolved by provider={}", ano, provider.providerName());
                return feriados;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for ano={} ({}). Cascading to next provider.",
                        provider.providerName(), ano, ex.getMessage());
            }
        }
        // Should be unreachable: the offline calculator never throws.
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Não foi possível obter os feriados para o ano solicitado.");
    }

    /**
     * Returns the next business day starting from {@code dataBase} (inclusive).
     * Skips Saturdays, Sundays, and any national holiday matching the date.
     * Crosses year boundaries cleanly by reloading the holiday set on demand.
     */
    public LocalDate calcularProximoDiaUtil(LocalDate dataBase) {
        int currentYear = dataBase.getYear();
        Set<LocalDate> feriadosDoAno = holidayDatesFor(currentYear);

        LocalDate candidato = dataBase;
        while (isWeekend(candidato) || feriadosDoAno.contains(candidato)) {
            candidato = candidato.plusDays(1);
            if (candidato.getYear() != currentYear) {
                currentYear = candidato.getYear();
                feriadosDoAno = holidayDatesFor(currentYear);
            }
        }
        return candidato;
    }

    /**
     * Calendar-day distance from {@code dataBase} to its resolved next
     * business day. Convenience helper for controllers.
     */
    public long diasAdicionados(LocalDate dataBase, LocalDate proximoDiaUtil) {
        return ChronoUnit.DAYS.between(dataBase, proximoDiaUtil);
    }

    private Set<LocalDate> holidayDatesFor(int ano) {
        Set<LocalDate> dates = new HashSet<>(16);
        for (FeriadoResponse feriado : findByAno(ano)) {
            dates.add(feriado.data());
        }
        return dates;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
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
