package br.com.cernebr.gateway_nacional.licitacoes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Linha enxuta de listagem ({@code /v1/licitacoes/ativas}).
 *
 * <p>Trazemos só o que um agregador (newsletter B2B, dashboard, scoring de
 * oportunidade) precisa para decidir se vale a pena abrir o detalhe. Todos
 * os campos longos ({@code itens}, {@code anexos}, descrição expandida)
 * ficam no {@link LicitacaoDetalheDTO} — o objetivo é manter a listagem
 * paginada barata em rede.</p>
 *
 * <p>Datas saem como {@link OffsetDateTime} em UTC ({@code Z}). Portais
 * publicam em America/Sao_Paulo; a normalização para offset explícito
 * acontece no client, antes de povoar o DTO — evita ambiguidade em fuso de
 * verão (historicamente DST entrou e saiu no Brasil) e segue a mesma
 * convenção do módulo Câmbio.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "LicitacaoResumoDTO",
        description = "Linha de listagem unificada de uma licitação ativa, normalizada entre os 4 portais cobertos.")
public record LicitacaoResumoDTO(
        @Schema(description = "Portal de origem (sempre preenchido).", example = "comprasnet")
        Portal portal,

        @Schema(description = "Identificador único usado no detalhe ({portal}/{identificador}).",
                example = "170531-05-000123-2026")
        String identificador,

        @Schema(description = "Número público publicado (ex.: 'Pregão Eletrônico 123/2026').",
                example = "Pregão Eletrônico 123/2026")
        String numero,

        @Schema(description = "Objeto resumido — primeiros 280 chars do edital.",
                example = "Aquisição de equipamentos de informática para a Secretaria Municipal de Educação.")
        String objetoResumido,

        @Schema(description = "Modalidade canônica (ver enum).", example = "pregao_eletronico")
        Modalidade modalidade,

        @Schema(description = "UF do órgão promotor.", example = "SP")
        String uf,

        @Schema(description = "Órgão promotor (resumo).")
        OrgaoDTO orgao,

        @Schema(description = "Abertura da sessão pública (timezone-aware, UTC).",
                example = "2026-06-15T13:00:00Z", format = "date-time")
        OffsetDateTime dataAbertura,

        @Schema(description = "Encerramento de propostas / homologação (timezone-aware, UTC).",
                example = "2026-06-15T18:00:00Z", format = "date-time")
        OffsetDateTime dataEncerramento,

        @Schema(description = "Valor total estimado em BRL, quando publicado.", example = "245000.00")
        BigDecimal valorEstimado,

        @Schema(description = "URL canônica para a página pública da licitação no portal de origem.",
                example = "https://www.gov.br/pncp/.../edital/170531-05-000123-2026")
        String urlOriginal
) {
}
