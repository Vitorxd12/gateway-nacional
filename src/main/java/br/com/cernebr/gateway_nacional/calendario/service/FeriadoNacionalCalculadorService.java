package br.com.cernebr.gateway_nacional.calendario.service;

import br.com.cernebr.gateway_nacional.calendario.client.FeriadoClientProvider;
import br.com.cernebr.gateway_nacional.calendario.dto.FeriadoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic offline calculator for Brazilian federal holidays. Acts as
 * the cascade's last resort — even with the entire internet unreachable,
 * the gateway can still answer.
 *
 * <p>Uses the <a href="https://en.wikipedia.org/wiki/Date_of_Easter#Anonymous_Gregorian_algorithm">
 * Meeus / Jones / Butcher</a> anonymous Gregorian algorithm to derive Easter
 * Sunday for any year, from which the moveable feasts are projected by fixed
 * day offsets. Combined with the static federal holidays, this yields the
 * complete national holiday list deterministically.</p>
 */
@Slf4j
@Service
public class FeriadoNacionalCalculadorService implements FeriadoClientProvider {

    public static final String PROVIDER_NAME = "in-memory-calculator";
    private static final String TIPO = "Nacional";

    @Override
    public List<FeriadoResponse> fetch(int ano) {
        return calcularFeriadosNacionais(ano);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Computes the canonical list of Brazilian federal holidays for the year.
     * Deterministic and side-effect free.
     */
    public List<FeriadoResponse> calcularFeriadosNacionais(int ano) {
        LocalDate pascoa = computeEasterSunday(ano);

        List<FeriadoResponse> feriados = new ArrayList<>(12);

        // Fixed federal holidays.
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 1, 1),  "Confraternização Universal", TIPO));
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 4, 21), "Tiradentes",                 TIPO));
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 5, 1),  "Dia do Trabalho",            TIPO));
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 9, 7),  "Independência do Brasil",    TIPO));
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 10, 12),"Nossa Senhora Aparecida",    TIPO));
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 11, 2), "Finados",                    TIPO));
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 11, 15),"Proclamação da República",   TIPO));
        // Lei nº 14.759/2023 elevou 20/11 (Dia Nacional de Zumbi e da Consciência Negra) a feriado nacional.
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 11, 20),"Consciência Negra",          TIPO));
        feriados.add(new FeriadoResponse(LocalDate.of(ano, 12, 25),"Natal",                      TIPO));

        // Movable feasts derived from Easter Sunday.
        feriados.add(new FeriadoResponse(pascoa.minusDays(47), "Carnaval",          TIPO));
        feriados.add(new FeriadoResponse(pascoa.minusDays(2),  "Sexta-feira Santa", TIPO));
        feriados.add(new FeriadoResponse(pascoa.plusDays(60),  "Corpus Christi",    TIPO));

        feriados.sort(Comparator.comparing(FeriadoResponse::data));
        return List.copyOf(feriados);
    }

    /**
     * Anonymous Gregorian algorithm (Meeus / Jones / Butcher). Returns the
     * date of Easter Sunday for the given Gregorian year.
     */
    private static LocalDate computeEasterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
