package br.com.cernebr.gateway_nacional.operacional.cptec.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CidadeCptecResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CondicaoAtualResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.OndasResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.PrevisaoClimaResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tier 1 — consulta direta ao XML legado do CPTEC/INPE
 * ({@code http://servicos.cptec.inpe.br/XML}). É o único provedor que cobre
 * 100% das rotas de clima brasileiras com dados de primeira mão; a
 * BrasilAPI atua como camada de redundância.
 *
 * <p><b>Por que HTTP (não HTTPS):</b> o INPE não publica certificado TLS no
 * subdomínio {@code servicos.cptec.inpe.br}. A BrasilAPI consome via HTTP
 * pelo mesmo motivo (ver {@code services/cptec/constants.js}). O risco de
 * tampering é baixo porque o conteúdo é meteorologia pública e o Circuit
 * Breaker isola a rota se o upstream picar.</p>
 *
 * <p><b>XML parser:</b> usamos {@link XmlMapper} configurado para ignorar
 * propriedades desconhecidas — o esquema CPTEC é frouxo e adiciona campos
 * sem aviso prévio.</p>
 */
@Slf4j
@Component
public class CptecInpeClient implements CptecClientProvider {

    public static final String PROVIDER_NAME = "CPTEC-INPE";

    private final RestClient restClient;
    private final XmlMapper xmlMapper;

    public CptecInpeClient(RestClient.Builder builder,
                           @Value("${gateway.cptec.inpe.base-url:http://servicos.cptec.inpe.br/XML}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_XML_VALUE)
                .build();
        this.xmlMapper = (XmlMapper) new XmlMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @CircuitBreaker(name = "cptecInpeCB", fallbackMethod = "fallbackSearch")
    public List<CidadeCptecResponse> searchCidades(String nome) {
        String body = fetchXml("/listaCidades?city=" + safe(nome));
        CidadesXml parsed = parse(body, CidadesXml.class);
        if (parsed == null || parsed.cidades == null) return List.of();
        return parsed.cidades.stream()
                .map(c -> new CidadeCptecResponse(c.nome, c.uf, CptecCatalogos.regiaoDe(c.uf), c.id))
                .toList();
    }

    @Override
    @CircuitBreaker(name = "cptecInpeCB", fallbackMethod = "fallbackCapitais")
    public List<CondicaoAtualResponse> condicoesCapitais() {
        String body = fetchXml("/capitais/condicoesAtuais.xml");
        CapitaisXml parsed = parse(body, CapitaisXml.class);
        if (parsed == null || parsed.metar == null) return List.of();
        return parsed.metar.stream().map(CptecInpeClient::toCondicao).toList();
    }

    @Override
    @CircuitBreaker(name = "cptecInpeCB", fallbackMethod = "fallbackAeroporto")
    public CondicaoAtualResponse condicoesAeroporto(String icao) {
        String body = fetchXml("/estacao/" + icao.toUpperCase(Locale.ROOT) + "/condicoesAtuais.xml");
        MetarWrapperXml parsed = parse(body, MetarWrapperXml.class);
        if (parsed == null || parsed.metar == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CPTEC sem METAR para o aeroporto " + icao);
        }
        return toCondicao(parsed.metar);
    }

    @Override
    @CircuitBreaker(name = "cptecInpeCB", fallbackMethod = "fallbackPrevisao")
    public PrevisaoClimaResponse previsao(int cityCode, int dias) {
        int clamped = Math.min(Math.max(dias, 1), CptecCatalogos.MAX_DAYS);
        String path = clamped <= 4
                ? "/cidade/" + cityCode + "/previsao.xml"
                : "/cidade/7dias/" + cityCode + "/previsao.xml";
        String body = fetchXml(path);
        CidadePrevisaoXml parsed = parse(body, CidadePrevisaoXml.class);
        if (parsed == null || parsed.nome == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CPTEC sem previsão para cityCode " + cityCode);
        }
        List<PrevisaoClimaResponse.DiaPrevisao> dias_ = (parsed.previsao == null ? List.<DiaXml>of() : parsed.previsao)
                .stream()
                .limit(clamped)
                .map(d -> new PrevisaoClimaResponse.DiaPrevisao(
                        d.dia,
                        d.tempo,
                        CptecCatalogos.describeCondicao(d.tempo),
                        d.minima,
                        d.maxima,
                        d.iuv))
                .toList();
        return new PrevisaoClimaResponse(parsed.nome, parsed.uf, parsed.atualizacao, dias_);
    }

    @Override
    @CircuitBreaker(name = "cptecInpeCB", fallbackMethod = "fallbackPrevisaoSemana")
    public PrevisaoClimaResponse previsaoSemana(double lat, double lon, int dias) {
        int clamped = Math.min(Math.max(dias, 1), CptecCatalogos.MAX_DAYS);
        // O INPE usa ponto decimal e expecta lat/lon com ":" não-separado; o
        // caminho é literal /cidade/7dias/{lat}/{long}/previsaoLatLon.xml,
        // mesma estrutura usada pela BrasilAPI internamente.
        String path = "/cidade/7dias/" + formatCoord(lat) + "/" + formatCoord(lon) + "/previsaoLatLon.xml";
        String body = fetchXml(path);
        CidadePrevisaoXml parsed = parse(body, CidadePrevisaoXml.class);
        if (parsed == null || parsed.nome == null || "null".equalsIgnoreCase(parsed.nome)) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CPTEC não localizou cidade para lat=" + lat + " long=" + lon);
        }
        List<PrevisaoClimaResponse.DiaPrevisao> dias_ = (parsed.previsao == null ? List.<DiaXml>of() : parsed.previsao)
                .stream()
                .limit(clamped)
                .map(d -> new PrevisaoClimaResponse.DiaPrevisao(
                        d.dia,
                        d.tempo,
                        CptecCatalogos.describeCondicao(d.tempo),
                        d.minima,
                        d.maxima,
                        d.iuv))
                .toList();
        return new PrevisaoClimaResponse(parsed.nome, parsed.uf, parsed.atualizacao, dias_);
    }

    @Override
    @CircuitBreaker(name = "cptecInpeCB", fallbackMethod = "fallbackOndas")
    public OndasResponse ondas(int cityCode, int dias) {
        int clamped = Math.min(Math.max(dias, 1), CptecCatalogos.MAX_DAYS);
        String body = fetchXml("/cidade/" + cityCode + "/todos/tempos/ondas.xml");
        CidadeOndasXml parsed = parse(body, CidadeOndasXml.class);
        if (parsed == null || parsed.previsao == null) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CPTEC sem boletim de ondas para cityCode " + cityCode);
        }
        // Agrupa por dia preservando ordem cronológica.
        Map<String, List<OndasResponse.MedidaOndas>> agrupado = new LinkedHashMap<>();
        for (OndaXml o : parsed.previsao) {
            if (o.dia == null) continue;
            String[] parts = o.dia.split("\\s+");
            String dia = parts[0];
            String hora = parts.length > 1 ? parts[1].replace("h", ":") + "00" : "00:00";
            String tz = parts.length > 2 ? parts[2] : "";
            OndasResponse.MedidaOndas medida = new OndasResponse.MedidaOndas(
                    hora + tz,
                    o.vento,
                    o.ventoDir,
                    CptecCatalogos.describeVento(o.ventoDir),
                    o.altura,
                    o.direcao,
                    CptecCatalogos.describeVento(o.direcao),
                    o.agitacao);
            agrupado.computeIfAbsent(dia, k -> new ArrayList<>()).add(medida);
        }
        List<OndasResponse.DiaOndas> diasOut = new ArrayList<>();
        for (Map.Entry<String, List<OndasResponse.MedidaOndas>> e : agrupado.entrySet()) {
            diasOut.add(new OndasResponse.DiaOndas(e.getKey(), e.getValue()));
            if (diasOut.size() >= clamped) break;
        }
        return new OndasResponse(parsed.nome, parsed.uf, parsed.atualizacao, diasOut);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private String fetchXml(String relativePath) {
        try {
            String body = restClient.get().uri(relativePath).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new ResourceUnavailableException(PROVIDER_NAME,
                        "CPTEC devolveu XML vazio para path=" + relativePath);
            }
            return body;
        } catch (ResourceUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Falha ao chamar CPTEC " + relativePath + ": " + ex.getMessage(), ex);
        }
    }

    private <T> T parse(String xml, Class<T> type) {
        try {
            return xmlMapper.readValue(xml, type);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "XML CPTEC ilegível ao mapear " + type.getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    private static String safe(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^A-Za-zÀ-ÿ0-9 \\-]", "");
    }

    private static CondicaoAtualResponse toCondicao(MetarXml m) {
        Objects.requireNonNull(m);
        return new CondicaoAtualResponse(
                m.codigo,
                m.atualizacao,
                m.pressao,
                m.ventoInt,
                m.ventoDir,
                m.umidade,
                m.tempo,
                CptecCatalogos.describeCondicao(m.tempo),
                m.temperatura);
    }

    // Fallbacks emitem 503 — o CptecService converte em cascata para BrasilAPI.
    @SuppressWarnings("unused")
    private List<CidadeCptecResponse> fallbackSearch(String nome, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "CPTEC indisponível (search cidades): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused")
    private List<CondicaoAtualResponse> fallbackCapitais(Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "CPTEC indisponível (capitais): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused")
    private CondicaoAtualResponse fallbackAeroporto(String icao, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "CPTEC indisponível (aeroporto): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused")
    private PrevisaoClimaResponse fallbackPrevisao(int cityCode, int dias, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "CPTEC indisponível (previsao): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused")
    private PrevisaoClimaResponse fallbackPrevisaoSemana(double lat, double lon, int dias, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "CPTEC indisponível (previsao semana lat/long): " + cause.getMessage(), cause);
    }
    @SuppressWarnings("unused")
    private OndasResponse fallbackOndas(int cityCode, int dias, Throwable cause) {
        throw new ResourceUnavailableException(PROVIDER_NAME, "CPTEC indisponível (ondas): " + cause.getMessage(), cause);
    }

    /**
     * Formata uma coordenada para o path do INPE — o upstream aceita ponto
     * decimal com até 4 casas e rejeita notação científica.
     */
    private static String formatCoord(double v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }

    // ─── XML bindings (private records / classes) ───────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CidadesXml {
        @JsonProperty("cidade")
        public List<CidadeXml> cidades;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CidadeXml {
        public String nome;
        public String uf;
        public Integer id;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CapitaisXml {
        @JsonProperty("metar")
        public List<MetarXml> metar;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MetarWrapperXml {
        public MetarXml metar;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MetarXml {
        public String codigo;
        public String atualizacao;
        public String pressao;
        @JsonProperty("vento_int") public String ventoInt;
        @JsonProperty("vento_dir") public String ventoDir;
        public String umidade;
        public String tempo;
        public String temperatura;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CidadePrevisaoXml {
        public String nome;
        public String uf;
        public String atualizacao;
        @JsonProperty("previsao") public List<DiaXml> previsao;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DiaXml {
        public String dia;
        public String tempo;
        public Integer minima;
        public Integer maxima;
        public Number iuv;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CidadeOndasXml {
        public String nome;
        public String uf;
        public String atualizacao;
        @JsonProperty("previsao") public List<OndaXml> previsao;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OndaXml {
        public String dia;
        public String vento;
        @JsonProperty("vento_dir") public String ventoDir;
        public String altura;
        public String direcao;
        public String agitacao;
    }

    // Optional getter used during exploratory testing — kept for symmetry.
    @SuppressWarnings("unused")
    public Optional<ObjectMapper> mapper() { return Optional.of(xmlMapper); }
}
