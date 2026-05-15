package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Contrato canônico unificado do módulo CNPJ.
 *
 * <p>Agrega Consulta Básica, QSA e Empresa em um único payload rico, mesclado
 * por {@code CnpjConsolidadoService} a partir de múltiplos providers
 * sobreviventes (CnpjWs, OpenCnpj, CnpjA, ReceitaWS, ArchiveCnpjPw). A lista
 * {@link #fontesSobreviventes()} é determinística e identifica exatamente quais
 * upstreams alimentaram o payload final.</p>
 *
 * <p><b>Normalizações garantidas pelo merge:</b></p>
 * <ul>
 *   <li>{@code ni} sempre 14 dígitos limpos.</li>
 *   <li>{@code capitalSocial} sempre em reais com 2 casas (centavos), mesmo
 *       quando o provider entrega o inteiro bruto do dump Serpro
 *       (divisão por 100 aplicada no parser).</li>
 *   <li>{@code tipoEstabelecimento} resolvido para {@code MATRIZ}/{@code FILIAL}
 *       a partir do código '1'/'2' ou texto.</li>
 *   <li>{@code qsa} retorna lista vazia (não null) para Empresário Individual
 *       (natureza jurídica {@code 213-5}).</li>
 * </ul>
 */
@Schema(name = "CnpjConsolidado",
        description = "Payload canônico unificado de CNPJ — agrega Consulta Básica, QSA e Empresa.")
public record CnpjConsolidadoDTO(
        @Schema(description = "NI (Número de Identificação) com 14 dígitos numéricos sem máscara.",
                example = "00000000000191")
        String ni,

        @Schema(description = "Tipo do estabelecimento (MATRIZ ou FILIAL).")
        TipoEstabelecimento tipoEstabelecimento,

        @Schema(description = "Nome empresarial (razão social).",
                example = "BANCO DO BRASIL SA")
        String nomeEmpresarial,

        @Schema(description = "Nome fantasia (nome de fachada).",
                example = "BB")
        String nomeFantasia,

        @Schema(description = "Situação cadastral consolidada (código, descrição, data e motivo).")
        SituacaoCadastralDTO situacaoCadastral,

        @Schema(description = "Natureza jurídica conforme tabela CONCLA.")
        NaturezaJuridicaDTO naturezaJuridica,

        @Schema(description = "Data de abertura do CNPJ (ISO-8601).",
                example = "1966-08-01")
        LocalDate dataAbertura,

        @Schema(description = "Atividade econômica principal (CNAE 2.3).")
        CnaeDTO cnaePrincipal,

        @Schema(description = "Atividades econômicas secundárias.")
        List<CnaeDTO> cnaeSecundarias,

        @Schema(description = "Endereço completo do estabelecimento.")
        EnderecoCompletoDTO enderecoCompleto,

        @Schema(description = "Telefones e correio eletrônico do estabelecimento.")
        ContatosDTO contatos,

        @Schema(description = "Capital social declarado em reais (já dividido por 100 quando " +
                "fonte é dump Serpro; sempre 2 casas decimais).",
                example = "120000000000.00")
        BigDecimal capitalSocial,

        @Schema(description = "Porte (MICRO EMPRESA, EMPRESA DE PEQUENO PORTE, DEMAIS).",
                example = "DEMAIS")
        String porte,

        @Schema(description = "Enquadramento Simples Nacional e MEI quando entregue por algum provider.")
        InformacoesSimplesMeiDTO informacoesSimplesMei,

        @Schema(description = "Quadro de Sócios e Administradores (até 300 entradas). " +
                "Documentos mascarados conforme LGPD.")
        List<SocioDTO> qsa,

        @Schema(description = "Lista determinística dos providers que entregaram dados " +
                "neste payload (auditoria de SLA).")
        List<String> fontesSobreviventes
) {

    /** Limite duro do parser de QSA — qualquer excedente é truncado. */
    public static final int MAX_SOCIOS = 300;
}
