package br.com.cernebr.gateway_nacional.operacional.rastreio.service;

import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.operacional.rastreio.client.BrasilApiRastreioClient;
import br.com.cernebr.gateway_nacional.operacional.rastreio.client.CorreiosOficialClient;
import br.com.cernebr.gateway_nacional.operacional.rastreio.client.LinkAndTrackClient;
import br.com.cernebr.gateway_nacional.operacional.rastreio.dto.RastreioResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolve um código de rastreio disparando Link&amp;Track, BrasilAPI e
 * Correios Oficial em paralelo via {@link HedgedExecutor}; vence o primeiro
 * a responder com sucesso.
 *
 * <p><b>Por que sem {@link br.com.cernebr.gateway_nacional.config.RefreshAheadCache}:</b>
 * o cache "rastreios" tem hard-TTL de apenas 1 hora — eventos de rastreio são
 * time-sensitive e uma janela maior arrisca enganar o cliente final. Soft-TTL
 * dentro de 1h não compraria muito (ou seria curto demais para refresh-ahead
 * fazer diferença, ou comprometeria a frescura). Mantemos {@code @Cacheable}
 * puro: cache hit serve direto, miss dispara o hedge novo.</p>
 *
 * <p>Cache key normaliza o código para uppercase via SpEL — {@code "lb123456789br"}
 * e {@code "LB123456789BR"} compartilham a mesma entrada Redis.</p>
 *
 * <p>Métricas de provider são emitidas pelo {@link HedgedExecutor}; este
 * service não duplica instrumentação.</p>
 */
@Slf4j
@Service
public class RastreioService {

    private static final String DOMAIN = "rastreio";

    private final LinkAndTrackClient linkAndTrack;
    private final BrasilApiRastreioClient brasilApi;
    private final CorreiosOficialClient correios;
    private final HedgedExecutor hedgedExecutor;

    public RastreioService(LinkAndTrackClient linkAndTrack,
                           BrasilApiRastreioClient brasilApi,
                           CorreiosOficialClient correios,
                           HedgedExecutor hedgedExecutor) {
        this.linkAndTrack = linkAndTrack;
        this.brasilApi = brasilApi;
        this.correios = correios;
        this.hedgedExecutor = hedgedExecutor;
    }

    @Cacheable(cacheNames = "rastreios", key = "#codigo.toUpperCase()")
    public RastreioResponse findByCodigo(String codigo) {
        return hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(linkAndTrack.providerName(), () -> linkAndTrack.fetch(codigo)),
                new NamedSupplier<>(brasilApi.providerName(),    () -> brasilApi.fetch(codigo)),
                new NamedSupplier<>(correios.providerName(),     () -> correios.fetch(codigo))
        ));
    }
}
