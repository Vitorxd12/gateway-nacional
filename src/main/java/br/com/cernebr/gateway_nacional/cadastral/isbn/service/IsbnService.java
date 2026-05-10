package br.com.cernebr.gateway_nacional.cadastral.isbn.service;

import br.com.cernebr.gateway_nacional.cadastral.isbn.client.BrasilApiIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.CblIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.GoogleBooksIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.MercadoEditorialIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.client.OpenLibraryIsbnClient;
import br.com.cernebr.gateway_nacional.cadastral.isbn.dto.IsbnResponse;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor;
import br.com.cernebr.gateway_nacional.config.HedgedExecutor.NamedSupplier;
import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Resolve um ISBN combinando dois padrões já estabelecidos no projeto:
 * <ol>
 *   <li>{@link RefreshAheadCache} aplica soft-TTL/hard-TTL: o cache "isbns"
 *       tem hard-TTL de 365 dias (livros são essencialmente imutáveis após
 *       publicação); o soft-TTL de 90 dias dispara refresh assíncrono em
 *       background — útil para o caso raro em que um provider corrige
 *       metadados (capa, sinopse) depois da primeira leitura.</li>
 *   <li>{@link HedgedExecutor} dispara os 5 providers em paralelo:
 *       <b>BrasilAPI (primário) + CBL + Google Books + Mercado Editorial +
 *       Open Library</b>. Vence o primeiro com sucesso.</li>
 * </ol>
 *
 * <h2>Por que BrasilAPI primário (RULE B)</h2>
 * <p>A própria BrasilAPI já agrega CBL/Google Books/Mercado Editorial/Open
 * Library internamente — quando ela está saudável, costuma vencer o hedge
 * por já ter feito a consolidação. Mantemos os 4 originais isolados como
 * rede de proteção: se a BrasilAPI cair, qualquer um dos 4 pode resolver.</p>
 *
 * <h2>Soft-TTL alto (90 dias)</h2>
 * <p>Diferente de CEP/CNPJ (dados que podem mudar), uma vez resolvido um
 * ISBN, os metadados não mudam — só correções pontuais de catalogação.
 * 90 dias entrega refresh oportunista sem desperdício para chaves frias
 * (livros raramente acessados não merecem recargas mensais).</p>
 *
 * <p>Métricas de provider são emitidas pelo {@link HedgedExecutor}; este
 * service não duplica instrumentação.</p>
 */
@Slf4j
@Service
public class IsbnService {

    private static final String DOMAIN = "isbn";
    private static final String CACHE_NAME = "isbns";
    private static final Duration SOFT_TTL = Duration.ofDays(90);

    private final BrasilApiIsbnClient brasilApi;
    private final CblIsbnClient cbl;
    private final GoogleBooksIsbnClient googleBooks;
    private final MercadoEditorialIsbnClient mercadoEditorial;
    private final OpenLibraryIsbnClient openLibrary;
    private final HedgedExecutor hedgedExecutor;
    private final RefreshAheadCache refreshAheadCache;

    public IsbnService(BrasilApiIsbnClient brasilApi,
                       CblIsbnClient cbl,
                       GoogleBooksIsbnClient googleBooks,
                       MercadoEditorialIsbnClient mercadoEditorial,
                       OpenLibraryIsbnClient openLibrary,
                       HedgedExecutor hedgedExecutor,
                       RefreshAheadCache refreshAheadCache) {
        this.brasilApi = brasilApi;
        this.cbl = cbl;
        this.googleBooks = googleBooks;
        this.mercadoEditorial = mercadoEditorial;
        this.openLibrary = openLibrary;
        this.hedgedExecutor = hedgedExecutor;
        this.refreshAheadCache = refreshAheadCache;
    }

    public IsbnResponse findByIsbn(String normalizedIsbn) {
        return refreshAheadCache.get(CACHE_NAME, normalizedIsbn, SOFT_TTL,
                () -> loadFromProviders(normalizedIsbn));
    }

    private IsbnResponse loadFromProviders(String isbn) {
        try {
            return hedgedExecutor.anyOf(DOMAIN, List.of(
                    new NamedSupplier<>(brasilApi.providerName(),        () -> brasilApi.fetch(isbn)),
                    new NamedSupplier<>(cbl.providerName(),              () -> cbl.fetch(isbn)),
                    new NamedSupplier<>(googleBooks.providerName(),      () -> googleBooks.fetch(isbn)),
                    new NamedSupplier<>(mercadoEditorial.providerName(), () -> mercadoEditorial.fetch(isbn)),
                    new NamedSupplier<>(openLibrary.providerName(),      () -> openLibrary.fetch(isbn))
            ));
        } catch (ResourceUnavailableException ex) {
            // Quando todos os providers exauriram, distinguimos:
            //   - "não localizado/encontrado" no cause → ISBN não existe → 404
            //   - "indisponível/Circuit Breaker aberto" → infra → 503
            // Os 5 clients seguem essa convenção textual estrita; auditar
            // qualquer client novo para manter a mesma linguagem.
            if (lastCauseSignalsNotFound(ex)) {
                throw new ResourceNotFoundException("ISBN",
                        "ISBN " + isbn + " não localizado em nenhum provider.");
            }
            throw ex;
        }
    }

    private static boolean lastCauseSignalsNotFound(Throwable ex) {
        Throwable cur = ex.getCause();
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("não localizado") || lower.contains("não encontrado")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
