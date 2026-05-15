package br.com.cernebr.gateway_nacional.veicular.avaliacao.service;

import br.com.cernebr.gateway_nacional.config.RefreshAheadCache;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.client.KbbClientProvider;
import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.PrecoKbbDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Camada de cache da Avaliação Técnica KBB. Envolve o
 * {@link KbbClientProvider} num {@link RefreshAheadCache} com chave
 * {@code kbb::{codigoFipe}::{anoModelo}}, hard TTL de 10 dias (definido no
 * {@code CacheConfig#kbbAvaliacao}) e soft TTL de 7 dias.
 *
 * <h2>Por que esse service existe</h2>
 * <p>O comentário em {@link AvaliacaoService} proíbe envolver o orquestrador
 * inteiro em RAC — fan-out de scrapers OLX/MobiAuto duplicaria trabalho
 * Selenium em background, o que é caro. O KBB é diferente: uma única
 * requisição determinística por {fipe, ano}, custosa (FlareSolverr +
 * Chromium) e com volatilidade baixa (a tabela KBB atualiza semanalmente,
 * não a cada segundo). Esse é exatamente o perfil em que RAC trabalha bem
 * — então o cache vive aqui, em torno do client, e não em volta do
 * orquestrador.</p>
 *
 * <h2>Janelas e dimensionamento</h2>
 * <ul>
 *   <li><b>Hard TTL 10 dias</b> (Redis) — descarte automático. KBB
 *       reprocessa sua tabela em ciclos semanais; 10 dias absorve o ciclo
 *       sem servir nada absurdamente antigo.</li>
 *   <li><b>Soft TTL 7 dias</b> (in-memory comparativo via {@link RefreshAheadCache})
 *       — entry vira "stale" e dispara refresh em background. Cliente atual
 *       serve o valor velho enquanto o background recarrega.</li>
 * </ul>
 *
 * <h2>Conservação de DTO</h2>
 * <p>Quando o client devolve {@link PrecoKbbDTO#disponivel()} == false
 * (KBB indisponível ou veículo não mapeado), <b>ainda assim cacheamos</b>
 * o resultado — porque um veículo não-mapeado provavelmente continuará
 * não-mapeado por dias, e martelar o KBB com a mesma consulta toda hora
 * é exatamente o que essa camada existe para evitar. O hard TTL menor
 * (10d) garante que correções eventuais entrem em janela razoável.</p>
 */
@Slf4j
@Service
public class KbbAvaliacaoService {

    /** Nome do cache configurado no {@code CacheConfig}. */
    public static final String CACHE_NAME = "kbbAvaliacao";

    /** Soft TTL — menor que o hard TTL configurado (10d) para o RAC engajar antes da expiração real. */
    private static final Duration SOFT_TTL = Duration.ofDays(7);

    private final KbbClientProvider kbbClient;
    private final RefreshAheadCache refreshAheadCache;

    public KbbAvaliacaoService(KbbClientProvider kbbClient,
                               RefreshAheadCache refreshAheadCache) {
        this.kbbClient = kbbClient;
        this.refreshAheadCache = refreshAheadCache;
    }

    /**
     * Avaliação Técnica KBB para um veículo. Sempre devolve um
     * {@link PrecoKbbDTO} — quando o KBB está indisponível ou o veículo não
     * existe na base, o DTO chega com {@code disponivel=false} e a
     * {@code mensagem} explicando. O orquestrador não precisa de
     * try/catch — basta encadear.
     */
    public PrecoKbbDTO fetchAvaliacao(String codigoFipe, String marca, String modelo, int anoModelo) {
        if (codigoFipe == null || codigoFipe.isBlank()) {
            return PrecoKbbDTO.indisponivel(null,
                    kbbClient.buildSearchUrl(null, marca, modelo, anoModelo),
                    "Código FIPE ausente — Avaliação KBB exige codigoFipe para resolver o veículo.");
        }
        String key = buildCacheKey(codigoFipe, anoModelo);
        try {
            return refreshAheadCache.get(CACHE_NAME, key, SOFT_TTL,
                    () -> kbbClient.fetchPreco(codigoFipe, marca, modelo, anoModelo));
        } catch (Exception ex) {
            // Defesa profunda — o client já tem CB e converte falhas, mas se algo
            // escapar não derruba o orquestrador. Volta indisponível.
            String url = kbbClient.buildSearchUrl(codigoFipe, marca, modelo, anoModelo);
            log.warn("KBB falhou de forma inesperada para {} ano={}: {}. Devolvendo indisponível.",
                    codigoFipe, anoModelo, ex.toString());
            return PrecoKbbDTO.indisponivel(codigoFipe, url,
                    "Avaliação KBB indisponível: " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Composição estável da chave: {@code kbb::{codigoFipe}::{anoModelo}}.
     * Mantida em {@link Locale#ROOT} para evitar diferenças entre locais
     * que tratam separadores. {@code codigoFipe} já chega no padrão
     * {@code 000000-0} pela validação do controller.
     */
    private static String buildCacheKey(String codigoFipe, int anoModelo) {
        return String.format(Locale.ROOT, "kbb::%s::%d", codigoFipe, anoModelo);
    }
}
