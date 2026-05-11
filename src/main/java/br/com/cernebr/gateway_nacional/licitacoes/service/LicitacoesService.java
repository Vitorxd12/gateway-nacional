package br.com.cernebr.gateway_nacional.licitacoes.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.licitacoes.client.BllComprasClient;
import br.com.cernebr.gateway_nacional.licitacoes.client.BncClient;
import br.com.cernebr.gateway_nacional.licitacoes.client.ComprasNetClient;
import br.com.cernebr.gateway_nacional.licitacoes.client.LicitacaoClient;
import br.com.cernebr.gateway_nacional.licitacoes.client.LicitanetClient;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoDetalheDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacaoResumoDTO;
import br.com.cernebr.gateway_nacional.licitacoes.dto.LicitacoesAtivasPage;
import br.com.cernebr.gateway_nacional.licitacoes.dto.Portal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Orquestra os 4 clients de licitação atrás do contrato canônico
 * {@code LicitacoesAtivasPage} / {@link LicitacaoDetalheDTO}.
 *
 * <h2>Cascata SEQUENCIAL (não hedge)</h2>
 * <p>Cada chamada à listagem agregada faz round-trip a 4 portais. ComprasNet
 * é REST oficial e barato; BLL é scraping pesado (HTML ~600KB). Disparar
 * em paralelo via {@code HedgedExecutor} elevaria 4× a carga sobre os
 * portais privados em todo refresh-ahead — política inaceitável para fontes
 * notoriamente frágeis (o ComprasNet "pisca" sob carga, e a BLL bloqueia
 * IPs que martelam). A cascata sequencial preserva a quota dos portais e
 * o RAC absorve a latência sob carga real: o refresh-ahead acontece em
 * background, o cliente sempre serve o cached.</p>
 *
 * <h2>RAC: Soft-TTL 30m / Hard-TTL 12h</h2>
 * <p>Licitações são publicadas em ondas (manhã e tarde de dias úteis). 30m
 * soft cobre o ciclo de quem refaz a query mais de uma vez por sessão; 12h
 * hard garante que ninguém vê listagem com mais de meio dia de defasagem
 * mesmo se todos os portais cairem simultaneamente. O Hard alinha com o
 * comportamento real do PNCP — refresh do índice ocorre em janelas de 6-12h.
 * </p>
 *
 * <h2>Degradação parcial</h2>
 * <p>Se um portal cai mas os outros 3 respondem, devolvemos a página com
 * {@code portaisFalhos} preenchido — o consumidor vê quais fontes faltam
 * e pode decidir se aceita a resposta parcial. 503 só é emitido quando os
 * 4 portais falham simultaneamente (situação extrema; cache stale já
 * cobre quase 100% dos casos).</p>
 */
@Slf4j
@Service
public class LicitacoesService {

    private static final String DOMAIN = "licitacoes";
    private static final String CACHE_ATIVAS = "licitacoesAtivas";
    private static final String CACHE_DETALHE = "licitacoesDetalhe";
    private static final Duration SOFT_TTL_ATIVAS = Duration.ofMinutes(30);
    private static final Duration SOFT_TTL_DETALHE = Duration.ofHours(2);

    static final String METRIC_REQUESTS = "gateway.provider.requests";
    static final String METRIC_LATENCY = "gateway.provider.latency";

    private final Map<Portal, LicitacaoClient> clients;
    private final RefreshAheadCache refreshAheadCache;
    private final MeterRegistry meterRegistry;

    public LicitacoesService(ComprasNetClient comprasNet,
                             BllComprasClient bll,
                             BncClient bnc,
                             LicitanetClient licitanet,
                             RefreshAheadCache refreshAheadCache,
                             MeterRegistry meterRegistry) {
        // Ordem de iteração estável (e auditável) na cascata sequencial:
        // 1º ComprasNet (REST oficial, mais rápido); 2º Licitanet (REST limpo);
        // 3º BNC (REST interno); 4º BLL (scraping, mais lento — fim da fila).
        this.clients = new java.util.LinkedHashMap<>();
        this.clients.put(Portal.COMPRASNET, comprasNet);
        this.clients.put(Portal.LICITANET, licitanet);
        this.clients.put(Portal.BNC, bnc);
        this.clients.put(Portal.BLL, bll);
        this.refreshAheadCache = refreshAheadCache;
        this.meterRegistry = meterRegistry;
    }

    /* ------------------------- Listagem agregada ------------------------- */

    public LicitacoesAtivasPage listarAtivas(String portalSlug, String uf, String modalidade) {
        String cacheKey = buildKey(portalSlug, uf, modalidade);
        return refreshAheadCache.get(CACHE_ATIVAS, cacheKey, SOFT_TTL_ATIVAS,
                () -> aggregateAtivas(portalSlug, uf, modalidade));
    }

    private LicitacoesAtivasPage aggregateAtivas(String portalSlug, String uf, String modalidade) {
        List<LicitacaoResumoDTO> agregadas = new ArrayList<>();
        List<String> portaisRespondidos = new ArrayList<>();
        List<String> portaisFalhos = new ArrayList<>();

        List<LicitacaoClient> alvos = resolveClients(portalSlug);

        // Cascata SEQUENCIAL — proteger quota dos portais. Falha individual
        // não interrompe os demais; agrega o sucesso parcial.
        for (LicitacaoClient client : alvos) {
            Timer.Sample sample = Timer.start(meterRegistry);
            String tag = client.providerName().toLowerCase(Locale.ROOT);
            try {
                List<LicitacaoResumoDTO> parcial = client.listarAtivas(uf, modalidade);
                agregadas.addAll(parcial);
                portaisRespondidos.add(client.portal().slug());
                recordOutcome(tag, "success", sample);
                log.info("[{}] {} resolveu {} licitações", DOMAIN, client.providerName(), parcial.size());
            } catch (ResourceUnavailableException ex) {
                portaisFalhos.add(client.portal().slug());
                recordOutcome(tag, "failure", sample);
                log.warn("[{}] {} indisponível: {}", DOMAIN, client.providerName(), ex.getMessage());
            } catch (RuntimeException ex) {
                portaisFalhos.add(client.portal().slug());
                recordOutcome(tag, "failure", sample);
                log.warn("[{}] {} falhou: {}", DOMAIN, client.providerName(), ex.toString());
            }
        }

        // Se TODOS os portais falharam, sobe 503 para o controller emitir
        // ProblemDetail. Cache stale, se houver, é servido antes de chegar
        // aqui (RAC ainda vê o entry dentro do hard-TTL).
        if (portaisRespondidos.isEmpty() && !portaisFalhos.isEmpty()) {
            throw new ResourceUnavailableException("Licitacoes",
                    "Todos os portais de licitação falharam: " + String.join(", ", portaisFalhos));
        }

        return new LicitacoesAtivasPage(
                agregadas,
                agregadas.size(),
                portaisRespondidos,
                portaisFalhos,
                Instant.now()
        );
    }

    /* --------------------------- Detalhe --------------------------- */

    public LicitacaoDetalheDTO detalhe(Portal portal, String identificador) {
        String cacheKey = portal.slug() + ":" + identificador;
        return refreshAheadCache.get(CACHE_DETALHE, cacheKey, SOFT_TTL_DETALHE,
                () -> loadDetalhe(portal, identificador));
    }

    private LicitacaoDetalheDTO loadDetalhe(Portal portal, String identificador) {
        LicitacaoClient client = clients.get(portal);
        if (client == null) {
            throw new ResourceNotFoundException("licitacao",
                    "Portal '" + portal.slug() + "' não está coberto pelo gateway.");
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        String tag = client.providerName().toLowerCase(Locale.ROOT);
        try {
            Optional<LicitacaoDetalheDTO> detalhe = client.buscarDetalhe(identificador);
            recordOutcome(tag, "success", sample);
            return detalhe.orElseThrow(() -> new ResourceNotFoundException("licitacao",
                    "Licitação '" + identificador + "' não localizada em " + portal.descricao() + "."));
        } catch (ResourceUnavailableException | ResourceNotFoundException ex) {
            recordOutcome(tag, ex instanceof ResourceUnavailableException ? "failure" : "success", sample);
            throw ex;
        } catch (RuntimeException ex) {
            recordOutcome(tag, "failure", sample);
            throw new ResourceUnavailableException(client.providerName(),
                    "Falha ao buscar detalhe no " + portal.descricao() + ": " + ex.getMessage(), ex);
        }
    }

    /* --------------------------- Helpers --------------------------- */

    private List<LicitacaoClient> resolveClients(String portalSlug) {
        if (portalSlug == null || portalSlug.isBlank()) {
            return new ArrayList<>(clients.values());
        }
        return Portal.fromSlug(portalSlug)
                .map(p -> List.of(clients.get(p)))
                .orElseThrow(() -> new ResourceNotFoundException("portal",
                        "Portal '" + portalSlug + "' não está coberto. Suportados: comprasnet, bll, bnc, licitanet."));
    }

    private String buildKey(String portal, String uf, String modalidade) {
        // Chaves estáveis e diff-friendly para Redis. "all" indica filtro
        // vazio — distingue do null que poderia colidir com outra chave.
        return (portal == null || portal.isBlank() ? "all" : portal.toLowerCase(Locale.ROOT))
                + ":" + (uf == null || uf.isBlank() ? "all" : uf.toUpperCase(Locale.ROOT))
                + ":" + (modalidade == null || modalidade.isBlank() ? "all" : modalidade.toLowerCase(Locale.ROOT));
    }

    private void recordOutcome(String providerTag, String outcome, Timer.Sample sample) {
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
