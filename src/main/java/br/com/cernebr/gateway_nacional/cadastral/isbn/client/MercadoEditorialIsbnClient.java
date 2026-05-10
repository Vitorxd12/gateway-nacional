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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provider FALLBACK 3 — Mercado Editorial v1.2 ({@code https://api.mercadoeditorial.org/api/v1.2/book?isbn={isbn}}).
 *
 * <p>Cobertura nacional brasileira; tipicamente expõe {@code retail_price}
 * em BRL e capa em alta resolução. Os campos vêm em português ({@code titulo},
 * {@code editora.nome_fantasia}, {@code contribuicao}, {@code medidas}) — a
 * conversão para o shape unificado é feita aqui.</p>
 */
@Slf4j
@Component
public class MercadoEditorialIsbnClient implements IsbnClientProvider {

    public static final String PROVIDER_NAME = "Mercado-Editorial";

    private final RestClient restClient;

    public MercadoEditorialIsbnClient(RestClient.Builder builder,
                                      @Value("${gateway.isbn.mercado-editorial.base-url:https://api.mercadoeditorial.org/api/v1.2}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "isbnMercadoEditorialCB", fallbackMethod = "fallback")
    public IsbnResponse fetch(String isbn) {
        MercadoEditorialResponse payload = restClient.get()
                .uri(uri -> uri.path("/book").queryParam("isbn", isbn).build())
                .retrieve()
                .body(MercadoEditorialResponse.class);

        if (payload == null || payload.books() == null || payload.books().isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "Mercado Editorial retornou corpo vazio ou ISBN não localizado.");
        }
        return mapToResponse(payload.books().get(0));
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private IsbnResponse fallback(String isbn, Throwable cause) {
        log.warn("Mercado Editorial fallback triggered for isbn={} cause={}", isbn, cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "Mercado Editorial indisponível ou Circuit Breaker aberto.", cause);
    }

    private static IsbnResponse mapToResponse(MercadoEditorialBook book) {
        List<String> authors = book.contribuicao() == null ? null
                : book.contribuicao().stream()
                .map(c -> (safe(c.nome()) + " " + safe(c.sobrenome())).trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        return new IsbnResponse(
                book.isbn(),
                book.titulo(),
                (book.subtitulo() == null || book.subtitulo().isBlank()) ? null : book.subtitulo(),
                authors,
                book.editora() == null ? null : book.editora().nomeFantasia(),
                book.sinopse(),
                parseDimensions(book.medidas()),
                parseInt(book.anoEdicao()),
                "BOOK".equals(book.formato()) ? "PHYSICAL" : "DIGITAL",
                book.medidas() == null ? null : parseInt(book.medidas().paginas()),
                parseSubjects(book.catalogacao()),
                null,
                parsePrice(book.moeda(), book.preco()),
                book.imagens() == null || book.imagens().imagemPrimeiraCapa() == null
                        ? null : book.imagens().imagemPrimeiraCapa().grande(),
                PROVIDER_NAME
        );
    }

    private static IsbnDimensions parseDimensions(MercadoEditorialMedidas medidas) {
        if (medidas == null || medidas.largura() == null || medidas.altura() == null) return null;
        try {
            return new IsbnDimensions(
                    Double.parseDouble(medidas.largura()),
                    Double.parseDouble(medidas.altura()),
                    "CENTIMETER");
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> parseSubjects(MercadoEditorialCatalogacao cat) {
        if (cat == null || cat.palavrasChave() == null || cat.palavrasChave().isBlank()) return null;
        return Arrays.stream(cat.palavrasChave().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static IsbnRetailPrice parsePrice(String currency, String price) {
        if (currency == null || currency.isBlank() || price == null || price.isBlank()) return null;
        try {
            return new IsbnRetailPrice(currency, new BigDecimal(price));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialResponse(List<MercadoEditorialBook> books) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialBook(
            String isbn,
            String titulo,
            String subtitulo,
            String sinopse,
            List<MercadoEditorialContribuicao> contribuicao,
            MercadoEditorialEditora editora,
            MercadoEditorialMedidas medidas,
            String ano_edicao,
            String formato,
            MercadoEditorialCatalogacao catalogacao,
            String moeda,
            String preco,
            MercadoEditorialImagens imagens
    ) {
        String anoEdicao() {
            return ano_edicao;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialContribuicao(String nome, String sobrenome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialEditora(String nome_fantasia) {
        String nomeFantasia() {
            return nome_fantasia;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialMedidas(String largura, String altura, String paginas) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialCatalogacao(String palavras_chave) {
        String palavrasChave() {
            return palavras_chave;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialImagens(MercadoEditorialCapa imagem_primeira_capa) {
        MercadoEditorialCapa imagemPrimeiraCapa() {
            return imagem_primeira_capa;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MercadoEditorialCapa(String grande) {
    }
}
