package br.com.cernebr.gateway_nacional.cadastral.isbn.client;

import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnDimensions;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;
import br.com.cernebr.gateway_nacional.cadastral.isbn.util.IsbnValidator;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider FALLBACK 1 — Câmara Brasileira do Livro (CBL) via Azure Cognitive
 * Search. Endpoint: {@code POST /indexes/isbn-index/docs/search?api-version=2016-09-01}.
 *
 * <p>A chave da CBL é a mesma usada pelo site oficial; aqui é embutida em
 * base64 (igual ao paradigma usado pela BrasilAPI) para não trafegar em texto
 * claro nas issues do projeto. Pode ser sobrescrita via
 * {@code GATEWAY_ISBN_CBL_API_KEY} para uso corporativo.</p>
 *
 * <p>O ISBN é convertido para AMBOS os formatos (10 e 13) e enviado como
 * {@code "{isbn13} OR {isbn10}"} em {@code search} — replica o comportamento
 * do site da CBL, que indexa pelos dois formatos sem padronização.</p>
 */
@Slf4j
@Component
public class CblIsbnClient implements IsbnClientProvider {

    public static final String PROVIDER_NAME = "CBL";

    private static final String SEARCH_PATH = "/indexes/isbn-index/docs/search?api-version=2016-09-01";
    private static final String DEFAULT_API_KEY_BASE64 = "MTAwMjE2QTIzQzVBRUUzOTAzMzhCQkQxOUVBODZEMjk=";

    private static final Pattern DIMENSIONS_PATTERN = Pattern.compile("(\\d{2})(\\d)?x(\\d{2})(\\d)?$");

    private final RestClient restClient;
    private final String apiKey;

    public CblIsbnClient(RestClient.Builder builder,
                         @Value("${gateway.isbn.cbl.base-url:https://isbn-search-br.search.windows.net}") String baseUrl,
                         @Value("${gateway.isbn.cbl.api-key:}") String apiKeyOverride) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKeyOverride.isBlank()
                ? new String(Base64.getDecoder().decode(DEFAULT_API_KEY_BASE64), StandardCharsets.UTF_8)
                : apiKeyOverride;
    }

    @Override
    @CircuitBreaker(name = "isbnCblCB", fallbackMethod = "fallback")
    public IsbnResponse fetch(String isbn) {
        String isbn13 = isbn.length() == 10 ? IsbnValidator.toIsbn13(isbn) : isbn;
        // ISBN-13 com prefixo 979 não tem ISBN-10 equivalente — tenta só 13.
        String search = "978".equals(isbn13.substring(0, 3))
                ? isbn13 + " OR " + IsbnValidator.toIsbn10(isbn13)
                : isbn13;

        Map<String, Object> body = Map.of(
                "count", true,
                "facets", List.of("Imprint,count:50", "Authors,count:50"),
                "filter", "",
                "queryType", "full",
                "search", search,
                "searchFields", "FormattedKey,RowKey",
                "searchMode", "any",
                "select", "*",
                "skip", 0,
                "top", 12
        );

        CblSearchResponse payload = restClient.post()
                .uri(SEARCH_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Api-Key", apiKey)
                .body(body)
                .retrieve()
                .body(CblSearchResponse.class);

        if (payload == null || payload.value() == null || payload.value().isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "CBL retornou corpo vazio ou ISBN não localizado.");
        }
        return mapToResponse(payload.value().get(0));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private IsbnResponse fallback(String isbn, Throwable cause) {
        log.warn("CBL fallback triggered for isbn={} cause={}", isbn, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "CBL indisponível ou Circuit Breaker aberto.", cause);
    }

    private static IsbnResponse mapToResponse(CblBook book) {
        List<String> subjects = new ArrayList<>();
        if (book.subject() != null && !book.subject().isBlank()) {
            subjects.add(book.subject());
        }
        if (book.palavrasChave() != null) {
            subjects.addAll(book.palavrasChave());
        }

        return new IsbnResponse(
                book.rowKey(),
                book.title(),
                book.subtitle(),
                book.authors(),
                book.imprint(),
                book.sinopse(),
                parseDimensions(book.dimensao()),
                parseInt(book.ano()),
                "Papel".equals(book.formato()) ? "PHYSICAL" : "DIGITAL",
                parseInt(book.paginas()),
                subjects.isEmpty() ? null : subjects,
                joinLocation(book.cidade(), book.uf()),
                null,
                null,
                PROVIDER_NAME
        );
    }

    private static IsbnDimensions parseDimensions(String raw) {
        if (raw == null) return null;
        Matcher m = DIMENSIONS_PATTERN.matcher(raw);
        if (!m.find()) return null;
        Double width = Double.parseDouble(m.group(1) + (m.group(2) != null ? "." + m.group(2) : ""));
        Double height = Double.parseDouble(m.group(3) + (m.group(4) != null ? "." + m.group(4) : ""));
        return new IsbnDimensions(width, height, "CENTIMETER");
    }

    private static String joinLocation(String city, String state) {
        if (city == null || city.isBlank() || state == null || state.isBlank()) return null;
        return city + ", " + state;
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CblSearchResponse(List<CblBook> value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CblBook(
            @JsonProperty("RowKey") String rowKey,
            @JsonProperty("Title") String title,
            @JsonProperty("Subtitle") String subtitle,
            @JsonProperty("Authors") List<String> authors,
            @JsonProperty("Imprint") String imprint,
            @JsonProperty("Sinopse") String sinopse,
            @JsonProperty("Dimensao") String dimensao,
            @JsonProperty("Ano") String ano,
            @JsonProperty("Formato") String formato,
            @JsonProperty("Paginas") String paginas,
            @JsonProperty("Subject") String subject,
            @JsonProperty("PalavrasChave") List<String> palavrasChave,
            @JsonProperty("Cidade") String cidade,
            @JsonProperty("UF") String uf
    ) {
    }
}
