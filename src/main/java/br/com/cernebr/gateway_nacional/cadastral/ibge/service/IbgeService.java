package br.com.cernebr.gateway_nacional.cadastral.ibge.service;

import br.com.cernebr.gateway_nacional.cadastral.ibge.client.BrasilApiIbgeClient;
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
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Resolve dados geográficos do IBGE com cache agressivo (RAC) e cascata
 * mandatória "interno primeiro, BrasilAPI só como rede de proteção".
 *
 * <h2>Cascata por operação</h2>
 *
 * <p><b>{@link #listAllUfs()}</b></p>
 * <ol>
 *   <li>Tier 1: {@link IbgeGovClient} (servicodados.ibge.gov.br)</li>
 *   <li>Tier 2: {@link BrasilApiIbgeClient} fallback</li>
 * </ol>
 *
 * <p><b>{@link #findUfByCode(String)}</b></p>
 * <ol>
 *   <li>Tier 1: {@link IbgeGovClient} + enriquecimento populacional via
 *       agregado v3 (best-effort — população falha silenciosamente)</li>
 *   <li>Tier 2: {@link BrasilApiIbgeClient} fallback (já entrega população
 *       embutida na mesma chamada — evita o 2º round-trip)</li>
 * </ol>
 *
 * <p><b>{@link #listMunicipiosByUf(String)}</b></p>
 * <ol>
 *   <li>Tier 1: <b>hedge</b> entre {@link IbgeGovClient} +
 *       {@link DadosAbertosBrClient} via {@link HedgedExecutor}</li>
 *   <li>Tier 2: {@link BrasilApiIbgeClient} fallback</li>
 * </ol>
 *
 * <p><b>Diretriz mandatória:</b> os providers diretos (gov, dados-abertos-br)
 * nunca são pulados em favor da BrasilAPI. A BrasilAPI só serve quando o
 * tier 1 inteiro está indisponível.</p>
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
    private final BrasilApiIbgeClient brasilApiFallbackClient;
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public IbgeService(IbgeGovClient govClient,
                       DadosAbertosBrClient dadosAbertosBrClient,
                       BrasilApiIbgeClient brasilApiFallbackClient,
                       HedgedExecutor hedgedExecutor,
                       RefreshAheadCache refreshAheadCache) {
        this.govClient = govClient;
        this.dadosAbertosBrClient = dadosAbertosBrClient;
        this.brasilApiFallbackClient = brasilApiFallbackClient;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    public List<UfResponse> listAllUfs() {
        UfsEnvelope envelope = refreshAheadCache.get(CACHE_UF, "all", UF_SOFT_TTL,
                this::loadAllUfsFromCascade);
        return envelope.ufs();
    }

    private UfsEnvelope loadAllUfsFromCascade() {
        try {
            return new UfsEnvelope(govClient.listAllUfs());
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("IBGE-Gov listAllUfs indisponível ({}). Cascateando pra BrasilAPI fallback.",
                    tier1Failure.getMessage());
            try {
                return new UfsEnvelope(brasilApiFallbackClient.listAllUfs());
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "listAllUfs");
            }
        }
    }

    public UfDetailResponse findUfByCode(String codeOrSigla) {
        return refreshAheadCache.get(CACHE_UF, "uf:" + codeOrSigla, UF_SOFT_TTL,
                () -> loadUfDetailFromCascade(codeOrSigla));
    }

    private UfDetailResponse loadUfDetailFromCascade(String codeOrSigla) {
        try {
            UfResponse uf = govClient.findUfByCodeOrSigla(codeOrSigla);
            // Enriquecimento populacional é best-effort — falha dele não
            // invalida a UF (population CB tem fallback retornando null).
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
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("IBGE-Gov findUf indisponível ({}). Cascateando pra BrasilAPI fallback (code={}).",
                    tier1Failure.getMessage(), codeOrSigla);
            try {
                // BrasilAPI já devolve UF + população em uma chamada — bônus.
                return brasilApiFallbackClient.findUfDetail(codeOrSigla);
            } catch (ResourceNotFoundException notFound) {
                throw notFound;
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "findUfByCode=" + codeOrSigla);
            }
        }
    }

    public List<MunicipioResponse> listMunicipiosByUf(String siglaUf) {
        MunicipiosEnvelope envelope = refreshAheadCache.get(
                CACHE_MUNICIPIOS, siglaUf, MUNICIPIOS_SOFT_TTL,
                () -> loadMunicipiosFromCascade(siglaUf));
        return envelope.municipios();
    }

    /**
     * Tier 1 = hedge entre 2 providers diretos (gov + dados-abertos-br).
     * Só falha quando AMBOS estão indisponíveis simultaneamente — só aí cai
     * pra BrasilAPI.
     */
    private MunicipiosEnvelope loadMunicipiosFromCascade(String siglaUf) {
        try {
            List<MunicipioResponse> raw = hedgedExecutor.anyOf(DOMAIN, List.of(
                    new NamedSupplier<>(govClient.providerName(),
                            () -> govClient.fetchByUf(siglaUf)),
                    new NamedSupplier<>(dadosAbertosBrClient.providerName(),
                            () -> dadosAbertosBrClient.fetchByUf(siglaUf))
            ));
            return normalize(raw);
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("IBGE municípios tier 1 (gov + dados-abertos-br) exausted para UF={} ({}). " +
                    "Cascateando pra BrasilAPI fallback.", siglaUf, tier1Failure.getMessage());
            try {
                return normalize(brasilApiFallbackClient.listMunicipiosByUf(siglaUf));
            } catch (ResourceNotFoundException notFound) {
                throw notFound;
            } catch (RuntimeException brasilApiFailure) {
                throw unify(tier1Failure, brasilApiFailure, "listMunicipios uf=" + siglaUf);
            }
        }
    }

    /**
     * Normalização final: nomes em UPPERCASE + ordenado por código IBGE.
     * Garante shape estável independente do provider vencedor.
     */
    private static MunicipiosEnvelope normalize(List<MunicipioResponse> raw) {
        List<MunicipioResponse> normalized = raw.stream()
                .map(m -> new MunicipioResponse(
                        m.nome() == null ? null : m.nome().toUpperCase(Locale.ROOT),
                        m.codigoIbge()))
                .sorted(Comparator.comparing(
                        m -> m.codigoIbge() == null ? "" : m.codigoIbge()))
                .toList();
        return new MunicipiosEnvelope(normalized);
    }

    private static ResourceUnavailableException unify(Throwable tier1, Throwable tier2, String context) {
        ResourceUnavailableException unified = new ResourceUnavailableException("ibge",
                "Providers diretos e BrasilAPI (fallback) falharam para " + context, tier2);
        unified.addSuppressed(tier1);
        return unified;
    }
}
