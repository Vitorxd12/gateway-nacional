package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Resultado de um fornecedor numa licitação do PNCP — a linha onde finalmente
 * aparece o CNPJ de empresa participante/vencedora (inexistente na federação
 * de editais ativos). Alimenta {@code participacao} + dispara o enriquecimento.
 *
 * <p>O endpoint {@code /resultados} do PNCP devolve os fornecedores HOMOLOGADOS
 * por item (têm {@code valorTotalHomologado} e {@code dataResultado}); a ordem
 * vem em {@code ordemClassificacaoSrp}.</p>
 */
public record PncpResultadoFornecedor(
        String niFornecedor,
        String nomeRazaoSocialFornecedor,
        String porteFornecedorNome,
        Integer ordemClassificacao,
        BigDecimal valorTotalHomologado,
        BigDecimal valorUnitarioHomologado,
        Integer numeroItem,
        String situacaoNome,
        OffsetDateTime dataResultado
) {
}
