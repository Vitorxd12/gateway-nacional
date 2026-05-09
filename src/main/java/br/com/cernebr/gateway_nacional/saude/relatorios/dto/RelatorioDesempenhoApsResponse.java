package br.com.cernebr.gateway_nacional.saude.relatorios.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Relatório consolidado de desempenho APS de um município no quadrimestre.
 * Combina o "Termômetro" do SISAB (nota Previne Brasil) com o cadastro CNES
 * dos estabelecimentos para entregar ao gestor municipal uma lista
 * priorizada de unidades que precisam de intervenção.
 *
 * <p><b>Valor de negócio</b>: o gestor recebe duas peças de informação que
 * historicamente vivem em portais separados — a nota financeira (impacta
 * repasse) e o mapa de unidades (onde agir) — pré-cruzadas. Isso encurta
 * o ciclo "ver problema → identificar unidade responsável → disparar busca
 * ativa" de horas (cruzamento manual de planilhas SISAB+CNES) para
 * milissegundos.</p>
 */
@Schema(name = "RelatorioDesempenhoApsResponse",
        description = "Termômetro APS do município + lista priorizada de estabelecimentos APS para busca ativa.")
public record RelatorioDesempenhoApsResponse(
        @Schema(description = "Município alvo do relatório.")
        Municipio municipio,

        @Schema(description = "Quadrimestre Previne Brasil/PMA no formato AAAAQq.", example = "2025Q3")
        String quadrimestre,

        @Schema(description = "Nota sintética consolidada (0-10) do município no quadrimestre.", example = "4.50")
        BigDecimal notaFinal,

        @Schema(description = "Verdadeiro quando a meta agregada do quadrimestre foi alcançada.", example = "false")
        boolean metaAlcancada,

        @ArraySchema(arraySchema = @Schema(description = "Lista de estabelecimentos APS do município, com status de risco derivado da nota."),
                schema = @Schema(implementation = UnidadeAlertaDTO.class))
        List<UnidadeAlertaDTO> unidadesAlerta
) {

    @Schema(name = "RelatorioDesempenhoApsResponse.Municipio",
            description = "Identificação cadastral do município consultado.")
    public record Municipio(
            @Schema(description = "Código IBGE como entregue na requisição (6 ou 7 dígitos).", example = "355030")
            String ibge,

            @Schema(description = "Nome do município.", example = "São Paulo")
            String nome,

            @Schema(description = "Sigla da UF derivada do prefixo do IBGE.", example = "SP")
            String uf
    ) {
    }
}
