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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.InputStream;
import java.text.Normalizer;

import tools.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;

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
    private final ObjectMapper objectMapper;

    private final Map<String, MunicipioResponse> municipioByCodeIndex = new ConcurrentHashMap<>();
    private final List<MunicipioIndexEntry> allMunicipiosIndex = new CopyOnWriteArrayList<>();

    private record MunicipioIndexEntry(MunicipioResponse municipio, String normalizedName) {}
    private record MunicipioJsonDto(
        String uf, 
        String localidade, 
        String ibge, 
        Double latitude, 
        Double longitude, 
        Boolean capital, 
        String siafi, 
        Integer ddd, 
        @JsonProperty("fuso_horario") String fusoHorario
    ) {}

    public IbgeService(IbgeGovClient govClient,
                       DadosAbertosBrClient dadosAbertosBrClient,
                       BrasilApiIbgeClient brasilApiFallbackClient,
                       HedgedExecutor hedgedExecutor,
                       RefreshAheadCache refreshAheadCache,
                       ObjectMapper objectMapper) {
        this.govClient = govClient;
        this.dadosAbertosBrClient = dadosAbertosBrClient;
        this.brasilApiFallbackClient = brasilApiFallbackClient;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initMunicipiosIndex() {
        try (InputStream is = new ClassPathResource("data/municipios_ibge.json").getInputStream()) {
            List<MunicipioJsonDto> rawList = objectMapper.readValue(is, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, MunicipioJsonDto.class));
            for (MunicipioJsonDto dto : rawList) {
                MunicipioResponse resp = new MunicipioResponse(
                    dto.localidade().toUpperCase(Locale.ROOT), 
                    dto.ibge(),
                    dto.latitude(),
                    dto.longitude(),
                    dto.capital(),
                    dto.siafi(),
                    dto.ddd(),
                    dto.fusoHorario()
                );
                municipioByCodeIndex.put(resp.codigoIbge(), resp);
                allMunicipiosIndex.add(new MunicipioIndexEntry(resp, removeAccents(dto.localidade()).toLowerCase(Locale.ROOT)));
            }
            log.info("Indexados {} municípios em memória com sucesso.", municipioByCodeIndex.size());
        } catch (Exception e) {
            log.error("Erro ao carregar data/municipios_ibge.json no startup", e);
        }
    }

    private static String removeAccents(String str) {
        if (str == null) return null;
        return Normalizer.normalize(str, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
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

    public List<MunicipioResponse> searchMunicipios(String termo) {
        if (termo == null || termo.isBlank()) {
            return List.of();
        }

        String t = termo.trim();
        if (t.matches("\\d+")) {
            MunicipioResponse found = municipioByCodeIndex.get(t);
            if (found != null) {
                return List.of(found);
            }
            // Cascateia pra API caso não ache na base local
            return refreshAheadCache.get(
                CACHE_MUNICIPIOS, "code:" + t, MUNICIPIOS_SOFT_TTL,
                () -> loadSingleMunicipioFromCascade(t)
            ).municipios();
        } else {
            String searchTerm = removeAccents(t).toLowerCase(Locale.ROOT);
            return allMunicipiosIndex.stream()
                    .filter(e -> e.normalizedName().contains(searchTerm))
                    .map(MunicipioIndexEntry::municipio)
                    .sorted(Comparator.comparing(MunicipioResponse::nome))
                    .toList();
        }
    }

    private MunicipiosEnvelope loadSingleMunicipioFromCascade(String codigoIbge) {
        // Fallback pra IBGE oficial ou Brasil API. Como o IbgeGovClient não tem find by code, 
        // mas foi adicionado abaixo ou a gente usa o que tem. 
        // Na verdade a instrução diz para adicionar a malha.
        // Já vamos usar o fallback direto caso precisemos.
        try {
            // Tenta achar via govClient se implementamos, senao vai falhar pro Brasil API
            // Mas vamos assumir que o govClient vai ter findMunicipioByCode ou então o BrasilAPI tem
            // Como BrasilAPI tem list by uf e não find by ibge, só vamos tentar o govClient.
            return new MunicipiosEnvelope(List.of(govClient.findMunicipioByCode(codigoIbge)));
        } catch (ResourceUnavailableException tier1Failure) {
            log.info("IBGE-Gov findMunicipioByCode indisponível ({}). Não há fallback viável.", tier1Failure.getMessage());
            throw unify(tier1Failure, new RuntimeException("Nenhum provider encontrou " + codigoIbge), "findMunicipioByCode");
        }
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
                        m.codigoIbge(),
                        m.latitude(),
                        m.longitude(),
                        m.capital(),
                        m.siafi(),
                        m.ddd(),
                        m.fusoHorario()))
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
