package br.com.cernebr.gateway_nacional.fiscal.ncm.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.fiscal.ncm.dto.NcmResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Secondary NCM provider — Portal Único Siscomex
 * ({@code https://portalunico.siscomex.gov.br}).
 *
 * <h2>Por que é um dump JSON inteiro</h2>
 * <p>O Portal Único descontinuou a consulta individual por código
 * ({@code /classif/api/publico/nomenclatura/{codigo}} retorna 404 em 2026-05).
 * O canal oficial passou a ser um arquivo JSON único atualizado diariamente,
 * contendo a tabela inteira ({@code Nomenclaturas} com ~15k itens, ~3 MB).
 * Este cliente baixa esse dump na hora, filtra em memória, e devolve o
 * resultado pelo contrato {@link NcmClientProvider}.</p>
 *
 * <h2>Estratégia de cache</h2>
 * <p>O cliente NÃO cacheia o dump em memória — cada chamada baixa os 3 MB
 * do governo. Isso é proposital: o {@code NcmService} cacheia o resultado
 * <i>final</i> ({@code Optional<NcmResponse>}) no Redis por 30 dias, então
 * o caminho lento (download + parse) só é exercido <b>uma vez por código
 * por mês</b> — exatamente quando o BrasilAPI cair e ainda não houver
 * cache Redis pra esse código específico. Em outage prolongado do BrasilAPI
 * com muitos códigos diferentes, esse padrão de "baixar tudo a cada miss"
 * vira gargalo; quando isso acontecer, mover o cache do dump para a
 * memória com TTL de 24h é o próximo passo natural.</p>
 *
 * <h2>Detalhes técnicos do upstream</h2>
 * <ul>
 *   <li>Responde HTTP 307 e redireciona para {@code ?perfil=PUBLICO}.
 *       O {@link RestClient} é configurado com {@link HttpClient.Redirect#NORMAL}
 *       para seguir o redirect automaticamente.</li>
 *   <li>Datas vêm em formato BR ({@code dd/MM/yyyy}) — o sentinela de
 *       vigência indeterminada é {@code 31/12/9999}, projetado para
 *       {@link LocalDate#of(int, int, int) LocalDate.of(9999, 12, 31)}.</li>
 *   <li>{@code Ano_Ato_Ini} chega como {@code String} (ex: {@code "2021"}),
 *       diferente do BrasilAPI que devolve int — convertemos no ACL.</li>
 * </ul>
 */
@Slf4j
@Component
public class SiscomexNcmClient implements NcmClientProvider {

    public static final String PROVIDER_NAME = "Siscomex";

    private static final String NOMENCLATURAS_FIELD = "Nomenclaturas";
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String url;

    public SiscomexNcmClient(RestClient.Builder builder,
                             @Value("${gateway.ncm.siscomex.url}") String url,
                             ObjectMapper objectMapper) {
        // O dump oficial responde 307 → o JDK HttpClient default não segue
        // redirects, então fixamos a política aqui. Spring's default RestClient
        // do projeto não tem essa configuração, mantemos um factory dedicado.
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.restClient = builder
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        this.url = url;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "siscomexNcmCB", fallbackMethod = "findByCodigoFallback")
    public Optional<NcmResponse> findByCodigo(String codigo) {
        String target = onlyDigits(codigo);
        if (target.isEmpty()) {
            return Optional.empty();
        }

        JsonNode nomenclaturas = downloadAndExtractList();
        for (JsonNode item : nomenclaturas) {
            String c = textOrNull(item.get("Codigo"));
            if (c != null && onlyDigits(c).equals(target)) {
                return Optional.of(toResponse(item));
            }
        }
        return Optional.empty();
    }

    @Override
    @CircuitBreaker(name = "siscomexNcmCB", fallbackMethod = "searchFallback")
    public List<NcmResponse> searchByDescricao(String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return List.of();
        }
        String needle = descricao.toLowerCase(Locale.ROOT);

        JsonNode nomenclaturas = downloadAndExtractList();
        List<NcmResponse> out = new ArrayList<>();
        for (JsonNode item : nomenclaturas) {
            String desc = textOrNull(item.get("Descricao"));
            if (desc != null && desc.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(toResponse(item));
            }
        }
        log.debug("Siscomex search '{}' → {} matches", descricao, out.size());
        return out;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private Optional<NcmResponse> findByCodigoFallback(String codigo, Throwable cause) {
        log.warn("Siscomex fallback (findByCodigo={}): {}", codigo, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Siscomex indisponível ou Circuit Breaker aberto.", cause);
    }

    @SuppressWarnings("unused")
    private List<NcmResponse> searchFallback(String descricao, Throwable cause) {
        log.warn("Siscomex fallback (search='{}'): {}", descricao, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Siscomex indisponível ou Circuit Breaker aberto.", cause);
    }

    /**
     * Baixa o dump oficial e devolve o nó {@code Nomenclaturas} (array). Falhas
     * de rede, redirect quebrado ou JSON em formato inesperado são todas
     * traduzidas para {@link ResourceUnavailableException} para o {@code @CircuitBreaker}
     * contar uniformemente.
     */
    private JsonNode downloadAndExtractList() {
        String body;
        try {
            body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Siscomex inacessível: " + ex.getClass().getSimpleName(), ex);
        }
        if (body == null || body.isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Siscomex devolveu corpo vazio.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Siscomex devolveu corpo não-JSON: " + ex.getMessage(), ex);
        }

        JsonNode list = root.get(NOMENCLATURAS_FIELD);
        if (list == null || !list.isArray()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Siscomex: campo '" + NOMENCLATURAS_FIELD + "' ausente ou não-array — schema mudou.");
        }
        return list;
    }

    private NcmResponse toResponse(JsonNode item) {
        return new NcmResponse(
                textOrNull(item.get("Codigo")),
                textOrNull(item.get("Descricao")),
                parseBrDate(item.get("Data_Inicio")),
                parseBrDate(item.get("Data_Fim")),
                textOrNull(item.get("Tipo_Ato_Ini")),
                textOrNull(item.get("Numero_Ato_Ini")),
                parseIntOrNull(item.get("Ano_Ato_Ini"))
        );
    }

    private static LocalDate parseBrDate(JsonNode n) {
        String s = textOrNull(n);
        if (s == null) return null;
        try {
            return LocalDate.parse(s, BR_DATE);
        } catch (DateTimeParseException ex) {
            log.debug("Siscomex date with unexpected format: {}", s);
            return null;
        }
    }

    private static Integer parseIntOrNull(JsonNode n) {
        String s = textOrNull(n);
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String txt = node.asText("").trim();
        return txt.isEmpty() ? null : txt;
    }

    /** Strips all non-digit characters — codes vary between {@code 33051000} and {@code 3305.10.00}. */
    private static String onlyDigits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }
}
