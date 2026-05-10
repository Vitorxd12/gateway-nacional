package br.com.cernebr.gateway_nacional.financeiro.cvm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Snapshot completo do dump CVM de corretoras, cacheado inteiro no Redis.
 *
 * <p>Wrapping de {@code List<>} é obrigatório para entrar no {@code RAC}
 * (gap do default-typing tratado pelo {@code ResilientGenericJacksonSerializer}).
 * Os dois endpoints públicos ({@code /cvm/corretoras} e
 * {@code /cvm/corretoras/{cnpj}}) operam sobre o mesmo snapshot — lookup por
 * CNPJ é feito em memória sobre a lista cacheada, evitando re-baixar o ZIP.</p>
 *
 * <p>{@code dataReferencia} é a data em que o dump foi efetivamente baixado
 * — útil para auditoria de "de quando é esse dado".</p>
 */
@Schema(name = "CvmCorretorasSnapshot", hidden = true)
public record CvmCorretorasSnapshot(
        List<CorretoraResponse> corretoras,
        LocalDate dataReferencia
) {
}
