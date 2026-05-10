package br.com.cernebr.gateway_nacional.financeiro.cvm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Snapshot completo do dump CVM de fundos, cacheado inteiro no Redis. Mesma
 * estratégia do {@link CvmCorretorasSnapshot}: a lista é populada uma vez por
 * ciclo de cache; lookup por CNPJ e paginação operam em memória sobre ela.
 *
 * <p>O dump da CVM tem ~30k fundos. Serializado em JSON, fica ~30MB —
 * grande, mas tolerável para Redis (Lettuce comprime). Se a serialização
 * virar gargalo, próxima iteração poderia usar SmartCache de 2 níveis
 * (in-memory na JVM + Redis backup).</p>
 */
@Schema(name = "CvmFundosSnapshot", hidden = true)
public record CvmFundosSnapshot(
        List<FundoDetailResponse> fundos,
        LocalDate dataReferencia
) {
}
