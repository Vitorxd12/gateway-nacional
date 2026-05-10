package br.com.cernebr.gateway_nacional.cadastral.isbn.client;

import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnDimensions;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider FALLBACK 4 — Open Library ({@code https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&jscmd=data&format=json}).
 *
 * <p>Cobertura mundial, base aberta. Costuma ter capa em alta resolução
 * mesmo para títulos antigos. {@code synopsis} fica frequentemente
 * {@code null} aqui — para tê-lo, BrasilAPI faz uma 2ª chamada a
 * {@code /isbn/{isbn}.json}; aqui usamos só a chamada principal para não
 * dobrar latência (se o cliente quiser sinopse, outro provider vence o hedge
 * com payload mais completo).</p>
 */
@Slf4j
@Component
public class OpenLibraryIsbnClient implements IsbnClientProvider {

    public static final String PROVIDER_NAME = "Open-Library";

    private static final Pattern DIMENSIONS_PATTERN =
            Pattern.compile("([\\d.]+)\\s*x\\s*([\\d.]+)\\s*x\\s*([\\d.]+)\\s*(centimeters|inches)?");

    private final RestClient restClient;

    public OpenLibraryIsbnClient(RestClient.Builder builder,
                                 @Value("${gateway.isbn.open-library.base-url:https://openlibrary.org}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "isbnOpenLibraryCB", fallbackMethod = "fallback")
    public IsbnResponse fetch(String isbn) {
        String bibKey = "ISBN:" + isbn;

        Map<String, OpenLibraryBook> payload = restClient.get()
                .uri(uri -> uri.path("/api/books")
                        .queryParam("bibkeys", bibKey)
                        .queryParam("jscmd", "data")
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, OpenLibraryBook>>() {});

        if (payload == null || payload.isEmpty() || !payload.containsKey(bibKey)) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Open Library retornou corpo vazio ou ISBN não localizado.");
        }
        return mapToResponse(isbn, payload.get(bibKey));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private IsbnResponse fallback(String isbn, Throwable cause) {
        log.warn("Open Library fallback triggered for isbn={} cause={}", isbn, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Open Library indisponível ou Circuit Breaker aberto.", cause);
    }

    private static IsbnResponse mapToResponse(String isbn, OpenLibraryBook book) {
        List<String> authors = book.authors() == null ? null
                : book.authors().stream().map(OpenLibraryAuthor::name).toList();
        String publisher = book.publishers() == null ? null
                : String.join(" & ", book.publishers().stream().map(OpenLibraryPublisher::name).toList());
        List<String> subjects = book.subjects() == null ? null
                : book.subjects().stream().map(OpenLibrarySubject::name).toList();
        String location = (book.publishPlaces() == null || book.publishPlaces().isEmpty())
                ? null : safeReplace(book.publishPlaces().get(0).name(), "Brazil", "Brasil");

        return new IsbnResponse(
                isbn,
                book.title(),
                book.subtitle(),
                authors,
                publisher,
                null,
                parseDimensions(book.physicalDimensions()),
                parseYear(book.publishDate()),
                "PHYSICAL",
                book.numberOfPages(),
                subjects,
                location,
                null,
                book.cover() == null ? null : book.cover().large(),
                PROVIDER_NAME
        );
    }

    private static IsbnDimensions parseDimensions(String raw) {
        if (raw == null) return null;
        Matcher m = DIMENSIONS_PATTERN.matcher(raw);
        if (!m.find()) return null;
        try {
            // Open Library publica como "altura x largura x espessura"; a BrasilAPI
            // mapeia width=group(2) height=group(1) — replicamos para paridade do
            // shape de saída.
            Double height = Double.parseDouble(m.group(1));
            Double width = Double.parseDouble(m.group(2));
            String unit = "inches".equalsIgnoreCase(m.group(4)) ? "INCH" : "CENTIMETER";
            return new IsbnDimensions(width, height, unit);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseYear(String publishDate) {
        if (publishDate == null) return null;
        Matcher m = Pattern.compile("\\d{4}").matcher(publishDate);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    private static String safeReplace(String value, String target, String replacement) {
        return value == null ? null : value.replace(target, replacement);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibraryBook(
            String title,
            String subtitle,
            List<OpenLibraryAuthor> authors,
            List<OpenLibraryPublisher> publishers,
            String publish_date,
            Integer number_of_pages,
            String physical_dimensions,
            List<OpenLibrarySubject> subjects,
            List<OpenLibraryPlace> publish_places,
            OpenLibraryCover cover
    ) {
        String publishDate() { return publish_date; }
        Integer numberOfPages() { return number_of_pages; }
        String physicalDimensions() { return physical_dimensions; }
        List<OpenLibraryPlace> publishPlaces() { return publish_places; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibraryAuthor(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibraryPublisher(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibrarySubject(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibraryPlace(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenLibraryCover(String small, String medium, String large) {
    }
}
