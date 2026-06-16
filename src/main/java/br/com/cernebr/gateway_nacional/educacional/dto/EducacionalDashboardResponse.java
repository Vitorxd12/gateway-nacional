package br.com.cernebr.gateway_nacional.educacional.dto;

import java.util.List;

public record EducacionalDashboardResponse(
        String uf,
        PnadIndicadores pnad,
        CensoIndicadores censo,
        FluxoEscolar fluxo,
        InfraestruturaEscolar infraestrutura,
        EnemSuperior enemSuperior,
        FndeRepasses fnde,
        ContextoSocioeconomico contexto,
        List<Cruzamento> insights
) {
    public record PnadIndicadores(
            Double taxaAnalfabetismo,
            Double mediaAnosEstudo,
            Double taxaFrequenciaEscolar
    ) {}

    public record CensoIndicadores(
            Integer totalEscolas,
            Integer totalMatriculas,
            Integer totalDocentes,
            Double idebMedio
    ) {}

    public record FluxoEscolar(
            Double taxaAprovacao,
            Double taxaReprovacao,
            Double taxaEvasao,
            Double distorcaoIdadeSerie
    ) {}

    public record InfraestruturaEscolar(
            Double percentualAcessoInternet,
            Double percentualLaboratorios,
            Double percentualAcessibilidade
    ) {}

    public record EnemSuperior(
            Double notaMediaEnem,
            Integer totalMatriculasSuperior,
            Integer concluintesSuperior
    ) {}

    public record FndeRepasses(
            Double valorPnae,
            Double valorPnate,
            Double valorPdde
    ) {}

    public record ContextoSocioeconomico(
            Double taxaJovensNemNem,
            Double percentualSuperiorCompleto,
            Double percentualFundamentalIncompleto
    ) {}

    public record Cruzamento(
            String titulo,
            String descricao,
            String severidade // ALTA, MEDIA, BAIXA
    ) {}
}
