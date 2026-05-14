package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Snapshot canônico da Avaliação Técnica KBB para um veículo. Aglutina os
 * dois canais de liquidação (revenda profissional vs. transação direta entre
 * pessoas físicas), o split por estado de conservação e a quilometragem-base
 * estimada que o KBB usa como ponto de partida para a banda.
 *
 * <p><b>Indisponibilidade graciosa:</b> quando o KBB não tem mapeamento para
 * o {fipeCode, ano} consultado, {@code disponivel} chega {@code false},
 * {@code precoTrocaLojista} e {@code precoParticular} vêm {@code null}, e
 * {@code mensagem} carrega a razão (ex: "Veículo não mapeado na base KBB
 * para o ano informado"). O endpoint nunca devolve 500 quando o veículo é
 * desconhecido — o consumidor decide se ignora ou se cai em outra fonte.</p>
 *
 * <p><b>Diferencial frente à FIPE:</b> a FIPE entrega um único valor médio
 * de tabela. O KBB entrega <i>quatro</i> ângulos comerciais — Lojista min,
 * Lojista max, Particular min, Particular max — e o multiplicador por
 * conservação fornece a 5ª dimensão. O delta entre {@code precoTrocaLojista.maximo}
 * e {@code precoParticular.minimo} é exatamente o spread que um vendedor
 * captura ao escolher vender por fora ao invés de aceitar uma proposta de
 * troca, e é a informação central que esse DTO existe para expor.</p>
 *
 * @see FaixaPrecoKbb
 * @see VariacaoConservacaoKbb
 */
@Schema(name = "PrecoKbbDTO",
        description = "Avaliação Técnica KBB — bandas Lojista vs. Particular, multiplicador por conservação, quilometragem-base estimada.")
public record PrecoKbbDTO(
        @Schema(description = "Código FIPE de referência usado para resolver o veículo na base KBB.",
                example = "005340-0")
        String fipeCodeReferencia,

        @Schema(description = "Faixa min/max do canal de revenda profissional (Lojista / Troca). **null** quando o KBB não publica esse canal para o veículo.",
                nullable = true)
        FaixaPrecoKbb precoTrocaLojista,

        @Schema(description = "Faixa min/max do canal de transação direta entre pessoas físicas. **null** quando o KBB não publica esse canal para o veículo.",
                nullable = true)
        FaixaPrecoKbb precoParticular,

        @Schema(description = "Multiplicadores por estado de conservação (EXCELENTE / BOM / REGULAR). **null** quando o KBB não publica o split.",
                nullable = true)
        VariacaoConservacaoKbb variacaoConservacao,

        @Schema(description = "Quilometragem-base anual que o KBB assume para a faixa publicada (típico: 15.000 km/ano × idade do veículo).",
                example = "60000", nullable = true)
        Integer quilometragemBaseEstimada,

        @Schema(description = "true quando o KBB devolveu dados utilizáveis; false sinaliza indisponibilidade graciosa.",
                example = "true")
        boolean disponivel,

        @Schema(description = "URL canônica consultada no portal KBB — exposta para auditoria do snapshot.",
                example = "https://www.kbb.com.br/precos/carro/005340-0/2018")
        String urlReferencia,

        @Schema(description = "Mensagem explicativa quando disponivel=false; vazio em respostas bem-sucedidas.",
                example = "Veículo não mapeado na base KBB para o ano informado.",
                nullable = true)
        String mensagem
) {

    /**
     * Constrói um snapshot de indisponibilidade — usado pelo cliente quando
     * o FlareSolverr está desligado, o circuito está aberto, o DOM mudou ou
     * o KBB simplesmente não tem o veículo. Garante que a propriedade
     * {@code disponivel=false} sempre venha acompanhada de uma {@code mensagem}
     * legível ao chamador.
     */
    public static PrecoKbbDTO indisponivel(String fipeCode, String urlReferencia, String mensagem) {
        return new PrecoKbbDTO(fipeCode, null, null, null, null, false, urlReferencia, mensagem);
    }
}
