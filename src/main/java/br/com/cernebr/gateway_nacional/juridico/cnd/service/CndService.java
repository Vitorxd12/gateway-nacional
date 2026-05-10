package br.com.cernebr.gateway_nacional.juridico.cnd.service;

import br.com.cernebr.gateway_nacional.juridico.cnd.client.CndSidecarClient;
import br.com.cernebr.gateway_nacional.juridico.cnd.dto.CndResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Façade do módulo CND.
 *
 * <p><b>ATENÇÃO: Não migrar para
 * {@link br.com.cernebr.gateway_nacional.config.HedgedExecutor} nem
 * {@link br.com.cernebr.gateway_nacional.config.RefreshAheadCache}.</b>
 * Provider de alto custo computacional (sidecar Python + Selenium navegando
 * formulário JSF da Receita Federal com ViewState e captcha). A cascata
 * sequencial — neste caso, single-provider — atua como proteção de recursos:
 * paralelizar dispararia múltiplas sessões Chromium por request e exauriria
 * a quota anti-bot do gov.br. RAC também não cabe (resultado é
 * timestamp-dependent, ver explicação abaixo).</p>
 *
 * <p>Sem {@code @Cacheable} aqui: o resultado de uma CND é um documento
 * datado por instante (a Receita renova diariamente o ViewState e a CND
 * tem código de controle único por emissão). Cachear levaria o ERP a
 * aceitar uma CND vencida ou a apresentar dois códigos de controle
 * conflitantes para o mesmo CNPJ. Cache aqui é regressão de correção,
 * não otimização.</p>
 */
@Slf4j
@Service
public class CndService {

    private static final String DOMAIN = "cnd";
    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final CndSidecarClient client;
    private final MeterRegistry meterRegistry;

    public CndService(CndSidecarClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    public CndResponse emitir(String cnpj, String tipo) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CndResponse response = client.emitir(cnpj, tipo);
            recordOutcome(client.providerName(), "success", sample);
            log.info("CND emitida cnpj={} tipo={} status={}", cnpj, tipo, response.status());
            return response;
        } catch (RuntimeException ex) {
            recordOutcome(client.providerName(), "failure", sample);
            log.warn("Provider {} falhou para CND cnpj={} tipo={} ({}).",
                    client.providerName(), cnpj, tipo, ex.getMessage());
            throw ex;
        }
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
