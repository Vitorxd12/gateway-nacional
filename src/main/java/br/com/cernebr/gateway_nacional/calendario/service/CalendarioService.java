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
 * <p>{@code siglaUf} is honored only by BrasilAPI (which exposes a state-aware
 * endpoint). The two fallback providers ignore it and return federal holidays
 * only. Consumers therefore lose <i>state-level</i> coverage exactly when the
 * primary upstream is unavailable — a deliberate degradation that keeps the
 * "next business day" computation conservative (skipping a regional holiday
 * the gateway cannot enumerate would silently return a non-banking day).</p>
 *
 * <p>Because the calculator is the last provider in the chain and never
 * fails, this service never throws {@link ResourceUnavailableException} for
 * the holiday lookup itself — the gateway always answers. The exception
 * exists in the signature only for unforeseen pathological inputs.</p>
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

    /**
     * Cache key is composite — {@code "{year}-{uf-or-BR}"} — so that a national
     * lookup and a state-scoped lookup never collide. The {@code siglaUf}
     * received here is already normalized (uppercase, blank → null) by the
     * controller.
     */
    @Cacheable(cacheNames = "feriados", key = "#ano + '-' + (#siglaUf != null ? #siglaUf : 'BR')")
    public List<FeriadoResponse> findByAno(int ano, String siglaUf) {
        for (FeriadoClientProvider provider : providersInOrder) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                List<FeriadoResponse> feriados = provider.fetch(ano, siglaUf);
                recordOutcome(provider.providerName(), "success", sample);
                log.info("Holidays for ano={} siglaUf={} resolved by provider={}",
                        ano, siglaUf, provider.providerName());
                return feriados;
            } catch (Exception ex) {
                recordOutcome(provider.providerName(), "failure", sample);
                log.warn("Provider {} failed for ano={} siglaUf={} ({}). Cascading to next provider.",
                        provider.providerName(), ano, siglaUf, ex.getMessage());
            }
        }
        // Should be unreachable: the offline calculator never throws.
        throw new ResourceUnavailableException(AGGREGATE_PROVIDER,
                "Não foi possível obter os feriados para o ano solicitado.");
    }

    /**
     * Returns the next business day starting from {@code dataBase} (inclusive).
     * Skips Saturdays, Sundays and holidays from the cascade — including
     * state-level ones when {@code siglaUf} is informed.
     * Crosses year boundaries cleanly by reloading the holiday set on demand.
     */
    public LocalDate calcularProximoDiaUtil(LocalDate dataBase, String siglaUf) {
        int currentYear = dataBase.getYear();
        Set<LocalDate> feriadosDoAno = holidayDatesFor(currentYear, siglaUf);

        LocalDate candidato = dataBase;
        while (isWeekend(candidato) || feriadosDoAno.contains(candidato)) {
            candidato = candidato.plusDays(1);
            if (candidato.getYear() != currentYear) {
                currentYear = candidato.getYear();
                feriadosDoAno = holidayDatesFor(currentYear, siglaUf);
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

    private Set<LocalDate> holidayDatesFor(int ano, String siglaUf) {
        Set<LocalDate> dates = new HashSet<>(16);
        for (FeriadoResponse feriado : findByAno(ano, siglaUf)) {
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
