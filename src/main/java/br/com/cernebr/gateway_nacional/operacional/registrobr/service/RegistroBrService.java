package br.com.cernebr.gateway_nacional.operacional.registrobr.service;

import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.operacional.registrobr.client.BrasilApiRegistroBrClient;
import br.com.cernebr.gateway_nacional.operacional.registrobr.client.RegistroBrOficialClient;
import br.com.cernebr.gateway_nacional.operacional.registrobr.dto.RegistroBrResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orquestra a consulta WHOIS/disponibilidade de domínios .br com hedge
 * paralelo entre o NIC.br (fonte canônica) e BrasilAPI (proxy normalizado).
 *
 * <p><b>Cache 10 minutos:</b> o domínio é um recurso que pode ser registrado
 * em segundos por terceiros, então TTL longo arrisca mascarar movimentações
 * reais. 10 minutos é o equilíbrio entre absorver consultas repetidas do
 * mesmo dashboard de monitoramento e refletir o estado real do registro.</p>
 */
@Slf4j
@Service
public class RegistroBrService {

    private static final String DOMAIN = "registroBr";
    private static final String CACHE = "registroBr";

    private final RegistroBrOficialClient oficial;
    private final BrasilApiRegistroBrClient brasilApi;
    private final HedgedExecutor hedgedExecutor;

    public RegistroBrService(RegistroBrOficialClient oficial,
                             BrasilApiRegistroBrClient brasilApi,
                             HedgedExecutor hedgedExecutor) {
        this.oficial = oficial;
        this.brasilApi = brasilApi;
        this.hedgedExecutor = hedgedExecutor;
    }

    @Cacheable(cacheNames = CACHE, key = "#dominio.toLowerCase()")
    public RegistroBrResponse consultar(String dominio) {
        String canonical = dominio.toLowerCase(Locale.ROOT);
        RegistroBrResponse out = hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(oficial.providerName(), () -> oficial.consultar(canonical)),
                new NamedSupplier<>(brasilApi.providerName(), () -> brasilApi.consultar(canonical))
        ));
        log.info("Registro.br resolvido para dominio={} status={}", canonical, out.status());
        return out;
    }
}
