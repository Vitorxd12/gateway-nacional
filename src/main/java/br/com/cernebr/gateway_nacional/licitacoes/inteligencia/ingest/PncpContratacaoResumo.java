package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Projeção normalizada de uma contratação do PNCP, suficiente para gravar o
 * snapshot {@code licitacao} e localizar a fase de resultados. Datas já em UTC.
 *
 * <p>É a fronteira entre o JSON cru do PNCP (records internos do
 * {@link PncpResultadosClient}) e o domínio da Inteligência — mantém o resto do
 * módulo desacoplado do formato do portal.</p>
 */
public record PncpContratacaoResumo(
        String cnpjOrgao,
        Integer anoCompra,
        Integer sequencialCompra,
        String numeroCompra,
        String objetoCompra,
        String modalidadeNome,
        BigDecimal valorTotalEstimado,
        BigDecimal valorTotalHomologado,
        OffsetDateTime dataAberturaProposta,
        OffsetDateTime dataEncerramentoProposta,
        OffsetDateTime dataPublicacaoPncp,
        String situacaoCompraNome,
        String orgaoNome,
        String orgaoUf,
        String municipioNome,
        String municipioIbge
) {

    /** Slug canônico {cnpjOrgao}-{ano}-{sequencial}, igual ao usado nas rotas REST. */
    public String identificador() {
        return cnpjOrgao + "-" + anoCompra + "-" + sequencialCompra;
    }
}
