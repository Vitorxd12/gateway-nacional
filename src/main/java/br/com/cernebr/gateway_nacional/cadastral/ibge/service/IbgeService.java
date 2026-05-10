package br.com.cernebr.gateway_nacional.cadastral.ibge.service;

import br.com.cernebr.gateway_nacional.cadastral.ibge.client.DadosAbertosBrClient;
import br.com.cernebr.gateway_nacional.cadastral.ibge.client.IbgeGovClient;
import br.com.cernebr.gateway_nacional.cadastral.ibge.client.IbgeGovClient.PopulacaoResult;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipiosEnvelope;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfDetailResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfResponse;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.UfsEnvelope;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Resolve dados geográficos do IBGE com cache agressivo (RAC) e, na operação
 * de municípios, hedge entre dois providers REST simples.
 *
 * <h2>Estratégia por operação</h2>
 * <ul>
 *   <li><b>{@link #listAllUfs()}</b> — single provider ({@link IbgeGovClient}).
 *       O dado é federal-fixo (27 UFs). RAC com hard-TTL 365d / soft 30d.</li>
 *   <li><b>{@link #findUfByCode(String)}</b> — single provider, enriquecido
 *       em paralelo com a estimativa populacional via outro endpoint do IBGE
 *       (agregado 6579). Population é best-effort — se falhar, devolve só a UF.</li>
 *   <li><b>{@link #listMunicipiosByUf(String)}</b> — <b>hedge</b> entre
 *       {@link IbgeGovClient} e {@link DadosAbertosBrClient}. Provider
 *       Wikipedia da BrasilAPI <em>não</em> entra (política PESADO — scraping
 *       HTML é caro e frágil). RAC com mesma janela das UFs.</li>
 * </ul>
 *
 * <h2>Por que cache agressivo</h2>
 * <p>Mudanças no quadro de UFs ou municípios são <em>lei federal</em>: o
 * último município criado foi Pinto Bandeira/RS em 2013, o último estado em
 * 1988 (Tocantins). Cache de 1 ano é mais que suficiente; o soft-TTL de 30d
 * dispara refresh oportunista para o caso (raríssimo) de uma alteração entrar
 * no IBGE.</p>
 */
@Slf4j
@Service
public class IbgeService {

    private static final String DOMAIN = "ibge";
    private static final String CACHE_UF = "ibgeUf";
    private static final String CACHE_MUNICIPIOS = "ibgeMunicipios";
    private static final Duration UF_SOFT_TTL = Duration.ofDays(30);
    private static final Duration MUNICIPIOS_SOFT_TTL = Duration.ofDays(30);

    private final IbgeGovClient govClient;
    private final DadosAbertosBrClient dadosAbertosBrClient;
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public IbgeService(IbgeGovClient govClient,
                       DadosAbertosBrClient dadosAbertosBrClient,
                       HedgedExecutor hedgedExecutor,
                       RefreshAheadCache refreshAheadCache) {
        this.govClient = govClient;
        this.dadosAbertosBrClient = dadosAbertosBrClient;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    public List<UfResponse> listAllUfs() {
        UfsEnvelope envelope = refreshAheadCache.get(CACHE_UF, "all", UF_SOFT_TTL,
                () -> new UfsEnvelope(govClient.listAllUfs()));
        return envelope.ufs();
    }

    /**
     * Busca uma UF por sigla (2 letras) ou código IBGE numérico, e enriquece
     * com a estimativa populacional mais recente.
     *
     * <p>O IBGE aceita ambos os formatos no path; passamos o input direto.
     * A normalização (uppercase para sigla) é feita no controller.</p>
     */
    public UfDetailResponse findUfByCode(String codeOrSigla) {
        return refreshAheadCache.get(CACHE_UF, "uf:" + codeOrSigla, UF_SOFT_TTL,
                () -> loadUfWithPopulation(codeOrSigla));
    }

    private UfDetailResponse loadUfWithPopulation(String codeOrSigla) {
        UfResponse uf = govClient.findUfByCodeOrSigla(codeOrSigla);
        // Enriquecimento populacional é best-effort: o ID do agregado precisa
        // ser numérico (id da UF), não a sigla. Se receber sigla, usa o id
        // descoberto pela primeira chamada.
        Integer ufCode = uf.id();
        PopulacaoResult populacao = ufCode != null
                ? govClient.estimatePopulationByUfCode(ufCode)
                : null;
        return new UfDetailResponse(
                uf.id(), uf.sigla(), uf.nome(),
                uf.regiaoSigla(), uf.regiaoNome(), uf.capital(),
                populacao == null ? null : populacao.populacaoEstimada(),
                populacao == null ? null : populacao.periodo()
        );
    }

    /**
     * Lista municípios da UF disparando os 2 providers REST em paralelo via
     * {@link HedgedExecutor}. Após o vencedor responder, normaliza nomes para
     * uppercase e ordena por código IBGE — convergência idêntica à BrasilAPI
     * (que também faz uppercase + sort).
     */
    public List<MunicipioResponse> listMunicipiosByUf(String siglaUf) {
        MunicipiosEnvelope envelope = refreshAheadCache.get(
                CACHE_MUNICIPIOS, siglaUf, MUNICIPIOS_SOFT_TTL,
                () -> loadMunicipiosFromProviders(siglaUf));
        return envelope.municipios();
    }

    private MunicipiosEnvelope loadMunicipiosFromProviders(String siglaUf) {
        List<MunicipioResponse> raw = hedgedExecutor.anyOf(DOMAIN, List.of(
                new NamedSupplier<>(govClient.providerName(),
                        () -> govClient.fetchByUf(siglaUf)),
                new NamedSupplier<>(dadosAbertosBrClient.providerName(),
                        () -> dadosAbertosBrClient.fetchByUf(siglaUf))
        ));

        // Normalização final: uppercase + ordem estável por código IBGE.
        // Garante que cliente recebe o mesmo shape independente do provider
        // que venceu o hedge.
        List<MunicipioResponse> normalized = raw.stream()
                .map(m -> new MunicipioResponse(
                        m.nome() == null ? null : m.nome().toUpperCase(Locale.ROOT),
                        m.codigoIbge()))
                .sorted(Comparator.comparing(
                        m -> m.codigoIbge() == null ? "" : m.codigoIbge()))
                .toList();
        return new MunicipiosEnvelope(normalized);
    }
}
