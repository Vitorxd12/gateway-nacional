package br.com.cernebr.gateway_nacional.licitacoes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Detalhe expandido de uma licitação ({@code /v1/licitacoes/{portal}/{id}}).
 * Inclui itens, anexos e o rótulo bruto da modalidade (útil em casos como
 * "Pregão Eletrônico SRP — Reabertura" que o {@link Modalidade#infer} dobra
 * para {@code PREGAO_ELETRONICO}, mas que o cliente pode querer ver intacto).
 *
 * <p>Estende — não substitui — o {@link LicitacaoResumoDTO}. Mantemos os
 * mesmos campos no topo para que clients downstream que já consomem o
 * resumo possam ler o detalhe sem refatoração; campos adicionais são
 * acrescentados ao fim do contrato.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "LicitacaoDetalheDTO",
        description = "Detalhe completo de uma licitação — itens, anexos, datas-marco e rótulos originais.")
public record LicitacaoDetalheDTO(
        @Schema(description = "Portal de origem.") Portal portal,

        @Schema(description = "Identificador único.") String identificador,

        @Schema(description = "Número público publicado.") String numero,

        @Schema(description = "Objeto integral do edital (sem corte).",
                example = "Aquisição de equipamentos de informática (notebooks, monitores e periféricos) para a Secretaria Municipal de Educação, conforme especificações técnicas constantes no Anexo I do edital.")
        String objeto,

        @Schema(description = "Modalidade canônica.") Modalidade modalidade,

        @Schema(description = "Rótulo bruto da modalidade publicado pelo portal — preserva nuances ('SRP', 'Reabertura', 'Sistema de Registro de Preços').",
                example = "Pregão Eletrônico — SRP")
        String modalidadeOriginal,

        @Schema(description = "UF do órgão promotor.", example = "SP") String uf,

        @Schema(description = "Órgão promotor (detalhado).") OrgaoDTO orgao,

        @Schema(description = "Abertura da sessão (UTC).", format = "date-time")
        OffsetDateTime dataAbertura,

        @Schema(description = "Encerramento / homologação prevista (UTC).", format = "date-time")
        OffsetDateTime dataEncerramento,

        @Schema(description = "Data de publicação do edital no portal (UTC).", format = "date-time")
        OffsetDateTime dataPublicacao,

        @Schema(description = "Valor total estimado em BRL, quando publicado.") BigDecimal valorEstimado,

        @Schema(description = "URL canônica da licitação no portal.") String urlOriginal,

        @Schema(description = "Situação publicada pelo portal (PUBLICADO, EM_JULGAMENTO, SUSPENSO, HOMOLOGADO, FRACASSADO, REVOGADO).",
                example = "PUBLICADO")
        String situacao,

        @Schema(description = "Lista de itens / lotes.")
        List<ItemLicitacaoDTO> itens,

        @Schema(description = "Lista de anexos publicados.")
        List<AnexoDTO> anexos
) {
}
