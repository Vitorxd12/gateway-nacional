package br.com.cernebr.gateway_nacional.veicular.avaliacao.client;

import br.com.cernebr.gateway_nacional.veicular.avaliacao.dto.PrecoKbbDTO;

/**
 * Contrato do provedor de Avaliação Técnica KBB. Separado do
 * {@link MercadoClientProvider} (que devolve apenas {@code List<BigDecimal>}
 * de anúncios) porque o KBB entrega estrutura — bandas por canal de
 * liquidação, multiplicadores por conservação e referência de quilometragem
 * — e amassar tudo num campo {@code precoMedio} jogaria fora exatamente o
 * diferencial técnico do provedor.
 *
 * <p><b>Falha graciosa é obrigatória:</b> implementações nunca devem lançar
 * exceção quando o veículo não existe no KBB ou quando o DOM divergiu dos
 * seletores. Devolva {@link PrecoKbbDTO#indisponivel(String, String, String)}
 * com mensagem explicativa. Exceção {@code ResourceUnavailableException} é
 * reservada para erros de infraestrutura (FlareSolverr fora, HTTP 5xx,
 * Circuit Breaker aberto) — situações em que tentar de novo mais tarde faz
 * sentido. Veículo desconhecido é estado permanente, não erro.</p>
 */
public interface KbbClientProvider {

    /**
     * Resolve a Avaliação Técnica KBB para o veículo identificado pelo
     * {@code codigoFipe} no {@code anoModelo} informado. {@code marca} e
     * {@code modelo} entram apenas como dica para fallback quando a busca
     * por código FIPE puro não encontrar nada no portal.
     *
     * @param codigoFipe código FIPE no padrão {@code 000000-0}; obrigatório
     * @param marca      hint textual de marca; opcional, usado como fallback
     * @param modelo     hint textual de modelo; opcional, usado como fallback
     * @param anoModelo  ano modelo (4 dígitos); obrigatório
     */
    PrecoKbbDTO fetchPreco(String codigoFipe, String marca, String modelo, int anoModelo);

    /**
     * URL canônica que será consultada para o veículo. Exposta em
     * {@link PrecoKbbDTO#urlReferencia()} para que humanos e ferramentas de
     * auditoria reproduzam o snapshot.
     */
    String buildSearchUrl(String codigoFipe, String marca, String modelo, int anoModelo);

    /** Identificador estável do provedor — usado em logs, métricas e tags de CB. */
    String providerName();
}
