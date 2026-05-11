package br.com.cernebr.gateway_nacional.saude.tuss.service;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.saude.tuss.client.BrasilApiTussClient;
import br.com.cernebr.gateway_nacional.saude.tuss.dto.TussCodigoResponse;
import br.com.cernebr.gateway_nacional.saude.tuss.dto.TussPageResponse;
import br.com.cernebr.gateway_nacional.saude.tuss.repository.TussLocalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Orquestra a consulta TUSS em cascata:
 *
 * <ol>
 *   <li><b>Tier 1 — BrasilAPI</b> ({@link BrasilApiTussClient}): proxy oficial
 *       da ANS, mantém o dicionário atualizado com cadência mensal.</li>
 *   <li><b>Tier 2 — Snapshot local</b> ({@link TussLocalRepository}): JSON
 *       embarcado no JAR como camada final de resiliência. Reflete o estado
 *       em que o snapshot foi construído; pode ficar atrás da BrasilAPI em
 *       algumas semanas, mas garante que o gateway sempre responde.</li>
 * </ol>
 *
 * <p>Falha do Tier 1 nunca propaga para o cliente final — degradamos para o
 * Tier 2 silenciosamente, instrumentando via log que a resposta veio do
 * snapshot local. Esse contrato torna o endpoint TUSS "sempre disponível"
 * — relevante para ERPs hospitalares que precisam validar códigos durante
 * o ciclo de autorização de procedimentos.</p>
 */
@Slf4j
@Service
public class TussService {

    private static final String CACHE = "tuss";
    private static final String PROVIDER_LOCAL = "Local-Snapshot";

    private final BrasilApiTussClient brasilApi;
    private final TussLocalRepository localRepo;

    public TussService(BrasilApiTussClient brasilApi, TussLocalRepository localRepo) {
        this.brasilApi = brasilApi;
        this.localRepo = localRepo;
    }

    @Cacheable(cacheNames = CACHE,
            key = "'detail:' + #codigo")
    public TussCodigoResponse findByCodigo(String codigo) {
        try {
            Optional<TussCodigoResponse> hit = brasilApi.findByCode(codigo);
            if (hit.isPresent()) {
                log.debug("TUSS {} resolvido via BrasilAPI", codigo);
                return hit.get();
            }
        } catch (ResourceUnavailableException ex) {
            log.warn("BrasilAPI TUSS indisponível para codigo={} — degradando para snapshot local: {}",
                    codigo, ex.getMessage());
        }

        return localRepo.findByCode(codigo)
                .map(row -> {
                    log.info("TUSS {} resolvido via snapshot local (BrasilAPI fora)", codigo);
                    return row;
                })
                .orElseThrow(() -> new ResourceNotFoundException("TUSS",
                        "Código TUSS " + codigo + " não consta no dicionário ANS."));
    }

    @Cacheable(cacheNames = CACHE,
            key = "'search:' + (#name == null ? '' : #name.toLowerCase()) + '|' + " +
                  "(#tuss == null ? '' : #tuss) + '|' + " +
                  "(#limit == null ? '' : #limit) + '|' + " +
                  "(#offset == null ? 0 : #offset)")
    public TussPageResponse search(String name, String tuss, Integer limit, Integer offset) {
        try {
            BrasilApiTussClient.BrasilApiPage page = brasilApi.listAndSearch(name, tuss, limit, offset);
            List<TussCodigoResponse> items = page.items().stream()
                    .map(r -> new TussCodigoResponse(r.tuss(), r.name()))
                    .toList();
            return new TussPageResponse(page.total(), page.limit(), page.offset(),
                    brasilApi.providerName(), items);
        } catch (ResourceUnavailableException ex) {
            log.warn("BrasilAPI TUSS indisponível em busca — degradando para snapshot local: {}",
                    ex.getMessage());
            return searchLocal(name, tuss, limit, offset);
        }
    }

    /**
     * Autocomplete dedicado para typeahead em prontuários/faturamento
     * hospitalar (campo "busque o procedimento", N keystrokes = N requests).
     *
     * <p><b>Por que sem {@code @Cacheable}:</b> o caminho rápido já é
     * sub-milissegundo — BrasilAPI normalmente responde em ~50 ms; o snapshot
     * local (in-memory) responde em &lt;1 ms. Adicionar um round-trip ao
     * Redis seria contraproducente para essa rota. O ganho real de cache
     * deve vir do lado do CLIENTE — daí o {@code Cache-Control: public,
     * max-age=300} emitido pelo controller, que faz o browser absorver os
     * keystrokes repetidos sem nem sair para o gateway.</p>
     *
     * <p><b>Cascata:</b> BrasilAPI primeiro (cobertura ANS canonical), snapshot
     * local como rede de proteção quando o CB do tier 1 abre. O upstream
     * BrasilAPI já força {@code match='prefix'} e {@code sort='tuss asc'};
     * o snapshot local replica a mesma semântica para resposta consistente
     * independente de qual tier respondeu.</p>
     */
    public List<TussCodigoResponse> autocomplete(String q, String name, String tuss, int limit) {
        try {
            return brasilApi.autocomplete(q, name, tuss, limit);
        } catch (ResourceUnavailableException ex) {
            log.warn("BrasilAPI TUSS autocomplete indisponível — degradando para snapshot local: {}",
                    ex.getMessage());
            return localRepo.autocomplete(q, name, tuss, limit);
        }
    }

    private TussPageResponse searchLocal(String name, String tuss, Integer limit, Integer offset) {
        List<TussCodigoResponse> all = localRepo.search(name, tuss);
        int from = offset == null || offset < 0 ? 0 : offset;
        int total = all.size();
        if (from >= total) {
            return new TussPageResponse(total, limit, from, PROVIDER_LOCAL, List.of());
        }
        int to = limit == null || limit <= 0 ? total : Math.min(from + limit, total);
        return new TussPageResponse(total, limit, from, PROVIDER_LOCAL, all.subList(from, to));
    }

    @SuppressWarnings("unused")
    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
