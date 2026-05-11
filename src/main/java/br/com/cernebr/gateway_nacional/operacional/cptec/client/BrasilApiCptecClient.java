package br.com.cernebr.gateway_nacional.operacional.cptec.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CidadeCptecResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CondicaoAtualResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.OndasResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.PrevisaoClimaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Tier 2 — proxy BrasilAPI {@code /api/cptec/v1/*}. A BrasilAPI já consolida
 * o XML legado do CPTEC e devolve JSON tipado, então o mapeamento aqui é
 * cosmético (renomes de snake_case → camelCase, derivação de descrições
 * via {@link CptecCatalogos}).
 */
@Slf4j
@Component
public class BrasilApiCptecClient implements CptecClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI-CPTEC";

    private final RestClient restClient;

    public BrasilApiCptecClient(RestClient.Builder builder,
                                @Value("${gateway.cptec.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "cptecBrasilApiCB", fallbackMethod = "fallbackSearch")
    public List<CidadeCptecResponse> searchCidades(String nome) {
        List<CidadeJson> body = restClient.get()
                .uri("/api/cptec/v1/cidade/{nome}", nome)
                .retrieve()
                .body(CIDADES_LIST);
        if (body == null) return List.of();
        return body.stream()
                .map(c -> new CidadeCptecResponse(c.nome, c.estado, c.regiao, c.id))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "cptecBrasilApiCB", fallbackMethod = "fallbackCapitais")
    public List<CondicaoAtualResponse> condicoesCapitais() {
        List<MetarJson> body = restClient.get()
                .uri("/api/cptec/v1/clima/capital")
                .retrieve()
                .body(METAR_LIST);
        if (body == null) return List.of();
        List<CondicaoAtualResponse> out = new ArrayList<>(body.size());
        for (MetarJson m : body) out.add(toCondicao(m));
        return out;
    }

    @Override
    @CircuitBreaker(name = "cptecBrasilApiCB", fallbackMethod = "fallbackAeroporto")
    public CondicaoAtualResponse condicoesAeroporto(String icao) {
        MetarJson body = restClient.get()
                .uri("/api/cptec/v1/clima/aeroporto/{icao}", icao)
                .retrieve()
                .body(MetarJson.class);
        if (body == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI sem METAR para " + icao);
        }
        return toCondicao(body);
    }

    @Override
    @CircuitBreaker(name = "cptecBrasilApiCB", fallbackMethod = "fallbackPrevisao")
    public PrevisaoClimaResponse previsao(int cityCode, int dias) {
        int clamped = Math.min(Math.max(dias, 1), CptecCatalogos.MAX_DAYS);
        PrevisaoJson body = restClient.get()
                .uri("/api/cptec/v1/clima/previsao/{code}/{dias}", cityCode, clamped)
                .retrieve()
                .body(PrevisaoJson.class);
        if (body == null || body.clima == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI sem previsão para " + cityCode);
        }
        List<PrevisaoClimaResponse.DiaPrevisao> dias_ = body.clima.stream()
                .map(d -> new PrevisaoClimaResponse.DiaPrevisao(
                        d.data, d.condicao, d.condicao_desc, d.min, d.max, d.indice_uv))
                .toList();
        return new PrevisaoClimaResponse(body.cidade, body.estado, body.atualizado_em, dias_);
    }

    @Override
    @CircuitBreaker(name = "cptecBrasilApiCB", fallbackMethod = "fallbackPrevisaoSemana")
    public PrevisaoClimaResponse previsaoSemana(double lat, double lon, int dias) {
        int clamped = Math.min(Math.max(dias, 1), CptecCatalogos.MAX_DAYS);
        PrevisaoJson body = restClient.get()
                .uri("/api/cptec/v1/clima/previsao/semana/{lat}/{lon}", lat, lon)
                .retrieve()
                .body(PrevisaoJson.class);
        if (body == null || body.clima == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI sem previsão para lat=" + lat + " lon=" + lon);
        }
        List<PrevisaoClimaResponse.DiaPrevisao> dias_ = body.clima.stream()
                .limit(clamped)
                .map(d -> new PrevisaoClimaResponse.DiaPrevisao(
                        d.data, d.condicao, d.condicao_desc, d.min, d.max, d.indice_uv))
                .toList();
        return new PrevisaoClimaResponse(body.cidade, body.estado, body.atualizado_em, dias_);
    }

    @Override
    @CircuitBreaker(name = "cptecBrasilApiCB", fallbackMethod = "fallbackOndas")
    public OndasResponse ondas(int cityCode, int dias) {
        int clamped = Math.min(Math.max(dias, 1), CptecCatalogos.MAX_DAYS);
        OndasJson body = restClient.get()
                .uri("/api/cptec/v1/ondas/{code}/{dias}", cityCode, clamped)
                .retrieve()
                .body(OndasJson.class);
        if (body == null || body.ondas == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI sem ondas para " + cityCode);
        }
        List<OndasResponse.DiaOndas> dias_ = body.ondas.stream()
                .map(d -> new OndasResponse.DiaOndas(d.data, d.dados_ondas == null ? List.of()
                        : d.dados_ondas.stream().map(m -> new OndasResponse.MedidaOndas(
                                m.hora, m.vento, m.direcao_vento, m.direcao_vento_desc,
                                m.altura_onda, m.direcao_onda, m.direcao_onda_desc, m.agitation))
                        .toList()))
                .toList();
        return new OndasResponse(body.cidade, body.estado, body.atualizado_em, dias_);
    }

    private static CondicaoAtualResponse toCondicao(MetarJson m) {
        return new CondicaoAtualResponse(
                m.codigo_icao,
                m.atualizado_em,
                m.pressao_atmosferica,
                m.vento,
                m.direcao_vento,
                m.umidade,
                m.condicao,
                m.condicao_desc != null ? m.condicao_desc : CptecCatalogos.describeCondicao(m.condicao),
                m.temp);
    }

    @SuppressWarnings("unused") private List<CidadeCptecResponse> fallbackSearch(String nome, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI CPTEC indisponível (cidades): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused") private List<CondicaoAtualResponse> fallbackCapitais(Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI CPTEC indisponível (capitais): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused") private CondicaoAtualResponse fallbackAeroporto(String icao, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI CPTEC indisponível (aeroporto): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused") private PrevisaoClimaResponse fallbackPrevisao(int cityCode, int dias, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI CPTEC indisponível (previsao): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused") private PrevisaoClimaResponse fallbackPrevisaoSemana(double lat, double lon, int dias, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI CPTEC indisponível (previsao semana lat/long): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused") private OndasResponse fallbackOndas(int cityCode, int dias, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "BrasilAPI CPTEC indisponível (ondas): " + cause.getMessage(), cause);
    }

    private static final ParameterizedTypeReference<List<CidadeJson>> CIDADES_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<MetarJson>> METAR_LIST = new ParameterizedTypeReference<>() {};

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CidadeJson(String nome, String estado, String regiao, Integer id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MetarJson(
            String codigo_icao, String atualizado_em, String pressao_atmosferica,
            String vento, String direcao_vento, String umidade,
            String condicao, String condicao_desc, String temp
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrevisaoJson(
            String cidade, String estado, String atualizado_em, List<DiaJson> clima
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiaJson(
            String data, String condicao, String condicao_desc,
            Integer min, Integer max, Number indice_uv
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OndasJson(
            String cidade, String estado, String atualizado_em, List<OndaDiaJson> ondas
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OndaDiaJson(String data, List<OndaMedidaJson> dados_ondas) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OndaMedidaJson(
            String hora, String vento, String direcao_vento, String direcao_vento_desc,
            String altura_onda, String direcao_onda, String direcao_onda_desc, String agitation
    ) {}
}
