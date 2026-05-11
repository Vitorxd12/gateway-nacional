package br.com.cernebr.gateway_nacional.licitacoes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Envelope cacheável da listagem agregada.
 *
 * <p><b>Por que envelope (e não {@code List<LicitacaoResumoDTO>} direto):</b>
 * o {@code RefreshAheadCache} envolve o valor num {@code CachedEntry<T>},
 * e o {@code GenericJackson2JsonRedisSerializer} usado pelo Spring Data
 * Redis falha em deserializar coleções na raiz (default-typing não cobre
 * {@code List} root). Embrulhar num record concreto contorna a limitação —
 * o cache permanece operacional e o consumidor recebe a lista pelo método
 * acessor.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "LicitacoesAtivasPage",
        description = "Envelope da listagem agregada de licitações ativas — inclui metadados de coleta.")
public record LicitacoesAtivasPage(
        @Schema(description = "Licitações resolvidas.")
        List<LicitacaoResumoDTO> resultados,

        @Schema(description = "Total absoluto de licitações resolvidas — equivale a resultados.size().",
                example = "47")
        int total,

        @Schema(description = "Portais que responderam com sucesso.", example = "[\"comprasnet\", \"bll\"]")
        List<String> portaisRespondidos,

        @Schema(description = "Portais que falharam (CB aberto, timeout). Listagem segue mesmo com parcial; consumidor decide se aceita degradação.",
                example = "[\"bnc\"]")
        List<String> portaisFalhos,

        @Schema(description = "Instante de coleta upstream (UTC).", example = "2026-05-11T13:45:00Z")
        Instant coletadoEm
) {
}
