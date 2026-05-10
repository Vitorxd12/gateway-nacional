package br.com.cernebr.gateway_nacional.cadastral.isbn.client;

import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnDimensions;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnRetailPrice;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Provider FALLBACK 2 — Google Books v1 ({@code https://www.googleapis.com/books/v1/volumes?q=isbn:{isbn}&country=BR}).
 *
 * <p>Cobertura mundial e tipicamente bons metadados (autores, capa,
 * categorias). Único provider que costuma expor {@code retail_price}, e
 * frequentemente entrega capa em alta resolução. Devolve o primeiro item
 * da lista — Google Books pode retornar múltiplas edições para um ISBN
 * de coleção, mas a primeira é sempre a edição-canônica daquele código.</p>
 */
@Slf4j
@Component
public class GoogleBooksIsbnClient implements IsbnClientProvider {

    public static final String PROVIDER_NAME = "Google-Books";

    private final RestClient restClient;

    public GoogleBooksIsbnClient(RestClient.Builder builder,
                                 @Value("${gateway.isbn.google-books.base-url:https://www.googleapis.com/books/v1}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "isbnGoogleBooksCB", fallbackMethod = "fallback")
    public IsbnResponse fetch(String isbn) {
        GoogleBooksResponse payload = restClient.get()
                .uri(uri -> uri.path("/volumes")
                        .queryParam("q", "isbn:" + isbn)
                        .queryParam("country", "BR")
                        .build())
                .retrieve()
                .body(GoogleBooksResponse.class);

        if (payload == null || payload.items() == null || payload.items().isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Google Books retornou corpo vazio ou ISBN não localizado.");
        }
        return mapToResponse(isbn, payload.items().get(0));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private IsbnResponse fallback(String isbn, Throwable cause) {
        log.warn("Google Books fallback triggered for isbn={} cause={}", isbn, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Google Books indisponível ou Circuit Breaker aberto.", cause);
    }

    private static IsbnResponse mapToResponse(String isbn, GoogleBooksItem item) {
        VolumeInfo info = item.volumeInfo();
        if (info == null || info.title() == null || info.title().isBlank()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Google Books devolveu item sem título — payload incompleto.");
        }

        return new IsbnResponse(
                isbn,
                info.title().trim(),
                null,
                info.authors(),
                info.publisher(),
                info.description(),
                parseDimensions(info.dimensions()),
                parseYearFromPublishedDate(info.publishedDate()),
                info.dimensions() != null ? "PHYSICAL" : "DIGITAL",
                info.pageCount(),
                info.categories(),
                null,
                parsePrice(item.saleInfo()),
                pickCoverUrl(info.imageLinks()),
                PROVIDER_NAME
        );
    }

    private static IsbnDimensions parseDimensions(GoogleBooksDimensions raw) {
        if (raw == null || raw.width() == null || raw.height() == null) return null;
        String widthStr = raw.width().replaceAll("\\s(cm|in)$", "");
        String heightStr = raw.height().replaceAll("\\s(cm|in)$", "");
        String unit = raw.width().contains("cm") ? "CENTIMETER" : "INCH";
        try {
            return new IsbnDimensions(Double.parseDouble(widthStr), Double.parseDouble(heightStr), unit);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseYearFromPublishedDate(String publishedDate) {
        if (publishedDate == null || publishedDate.length() < 4) return null;
        try {
            return Integer.parseInt(publishedDate.substring(0, 4));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static IsbnRetailPrice parsePrice(SaleInfo saleInfo) {
        if (saleInfo == null || saleInfo.retailPrice() == null
                || "NOT_FOR_SALE".equals(saleInfo.saleability())) {
            return null;
        }
        return new IsbnRetailPrice(saleInfo.retailPrice().currencyCode(), saleInfo.retailPrice().amount());
    }

    private static String pickCoverUrl(ImageLinks links) {
        if (links == null) return null;
        String url = firstNonBlank(links.extraLarge(), links.large(), links.medium(),
                links.small(), links.thumbnail(), links.smallThumbnail());
        // Google serve as imagens em http por default — força https para não quebrar
        // browser modernos com mixed-content blocking.
        return url == null ? null : url.replace("http://", "https://");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleBooksResponse(List<GoogleBooksItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleBooksItem(VolumeInfo volumeInfo, SaleInfo saleInfo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VolumeInfo(
            String title,
            List<String> authors,
            String publisher,
            String description,
            String publishedDate,
            Integer pageCount,
            List<String> categories,
            GoogleBooksDimensions dimensions,
            ImageLinks imageLinks
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleBooksDimensions(String width, String height) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageLinks(String smallThumbnail, String thumbnail, String small,
                              String medium, String large, String extraLarge) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SaleInfo(String saleability, RetailPrice retailPrice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RetailPrice(String currencyCode, BigDecimal amount) {
    }
}
