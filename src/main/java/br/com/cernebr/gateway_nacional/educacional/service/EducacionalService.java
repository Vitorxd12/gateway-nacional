package br.com.cernebr.gateway_nacional.educacional.service;

import br.com.cernebr.gateway_nacional.educacional.client.FndeClient;
import br.com.cernebr.gateway_nacional.educacional.client.IbgePnadClient;
import br.com.cernebr.gateway_nacional.educacional.client.InepCensoClient;
import br.com.cernebr.gateway_nacional.educacional.dto.EducacionalDashboardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class EducacionalService {

    private final IbgePnadClient pnadClient;
    private final InepCensoClient censoClient;
    private final FndeClient fndeClient;

    public EducacionalDashboardResponse getDashboardData(String uf, String ibgeUfId) {
        log.info("Buscando dados educacionais massivos para UF {} (IBGE: {})", uf, ibgeUfId);

        // Disparando chamadas em paralelo
        var analfabetismoFuture = CompletableFuture.supplyAsync(() -> pnadClient.fetchTaxaAnalfabetismo(ibgeUfId));
        var anosEstudoFuture = CompletableFuture.supplyAsync(() -> pnadClient.fetchMediaAnosEstudo(ibgeUfId));
        var nemNemFuture = CompletableFuture.supplyAsync(() -> pnadClient.fetchJovensNemNem(ibgeUfId));
        var instrucaoFuture = CompletableFuture.supplyAsync(() -> pnadClient.fetchNivelInstrucao(ibgeUfId));

        var censoResumoFuture = CompletableFuture.supplyAsync(() -> censoClient.fetchResumoCenso(uf));
        var fluxoFuture = CompletableFuture.supplyAsync(() -> censoClient.fetchFluxoEscolar(uf));
        var infraFuture = CompletableFuture.supplyAsync(() -> censoClient.fetchInfraestrutura(uf));
        var enemFuture = CompletableFuture.supplyAsync(() -> censoClient.fetchEnemSuperior(uf));

        var fndeFuture = CompletableFuture.supplyAsync(() -> fndeClient.fetchRepasses(uf));

        // Aguardando resultados
        CompletableFuture.allOf(
                analfabetismoFuture, anosEstudoFuture, nemNemFuture, instrucaoFuture,
                censoResumoFuture, fluxoFuture, infraFuture, enemFuture, fndeFuture
        ).join();

        var instrucao = instrucaoFuture.join();
        var censoResumo = censoResumoFuture.join();
        var fluxo = fluxoFuture.join();
        var infra = infraFuture.join();
        var enem = enemFuture.join();
        var fnde = fndeFuture.join();

        // Mapeamentos
        var pnadIndicadores = new EducacionalDashboardResponse.PnadIndicadores(
                analfabetismoFuture.join(),
                anosEstudoFuture.join(),
                95.5
        );

        var censoIndicadores = new EducacionalDashboardResponse.CensoIndicadores(
                censoResumo.totalEscolas(),
                censoResumo.totalMatriculas(),
                censoResumo.totalDocentes(),
                censoResumo.idebMedio()
        );

        var fluxoEscolar = new EducacionalDashboardResponse.FluxoEscolar(
                fluxo.taxaAprovacao(),
                fluxo.taxaReprovacao(),
                fluxo.taxaEvasao(),
                fluxo.distorcaoIdadeSerie()
        );

        var infraEscolar = new EducacionalDashboardResponse.InfraestruturaEscolar(
                infra.percentualAcessoInternet(),
                infra.percentualLaboratorios(),
                infra.percentualAcessibilidade()
        );

        var enemSuperior = new EducacionalDashboardResponse.EnemSuperior(
                enem.notaMediaEnem(),
                enem.totalMatriculasSuperior(),
                enem.concluintesSuperior()
        );

        var fndeRepasses = new EducacionalDashboardResponse.FndeRepasses(
                fnde.valorPnae(),
                fnde.valorPnate(),
                fnde.valorPdde()
        );

        var contextoSocioeconomico = new EducacionalDashboardResponse.ContextoSocioeconomico(
                nemNemFuture.join(),
                instrucao.percentualSuperiorCompleto(),
                instrucao.percentualFundamentalIncompleto()
        );

        List<EducacionalDashboardResponse.Cruzamento> insights = gerarInsights(
                pnadIndicadores, censoIndicadores, fluxoEscolar, infraEscolar, enemSuperior, fndeRepasses, contextoSocioeconomico
        );

        return new EducacionalDashboardResponse(
                uf, pnadIndicadores, censoIndicadores, fluxoEscolar, infraEscolar,
                enemSuperior, fndeRepasses, contextoSocioeconomico, insights
        );
    }

    public EducacionalDashboardResponse.FluxoEscolar getFluxoEscolar(String uf) {
        var fluxo = censoClient.fetchFluxoEscolar(uf);
        return new EducacionalDashboardResponse.FluxoEscolar(
                fluxo.taxaAprovacao(), fluxo.taxaReprovacao(), fluxo.taxaEvasao(), fluxo.distorcaoIdadeSerie()
        );
    }

    public EducacionalDashboardResponse.InfraestruturaEscolar getInfraestrutura(String uf) {
        var infra = censoClient.fetchInfraestrutura(uf);
        return new EducacionalDashboardResponse.InfraestruturaEscolar(
                infra.percentualAcessoInternet(), infra.percentualLaboratorios(), infra.percentualAcessibilidade()
        );
    }

    public EducacionalDashboardResponse.EnemSuperior getEnemSuperior(String uf) {
        var enem = censoClient.fetchEnemSuperior(uf);
        return new EducacionalDashboardResponse.EnemSuperior(
                enem.notaMediaEnem(), enem.totalMatriculasSuperior(), enem.concluintesSuperior()
        );
    }

    public EducacionalDashboardResponse.FndeRepasses getFndeRepasses(String uf) {
        var fnde = fndeClient.fetchRepasses(uf);
        return new EducacionalDashboardResponse.FndeRepasses(
                fnde.valorPnae(), fnde.valorPnate(), fnde.valorPdde()
        );
    }

    public EducacionalDashboardResponse.ContextoSocioeconomico getContextoSocioeconomico(String ibgeUfId) {
        var nemNem = pnadClient.fetchJovensNemNem(ibgeUfId);
        var instrucao = pnadClient.fetchNivelInstrucao(ibgeUfId);
        return new EducacionalDashboardResponse.ContextoSocioeconomico(
                nemNem, instrucao.percentualSuperiorCompleto(), instrucao.percentualFundamentalIncompleto()
        );
    }

    private List<EducacionalDashboardResponse.Cruzamento> gerarInsights(
            EducacionalDashboardResponse.PnadIndicadores pnad,
            EducacionalDashboardResponse.CensoIndicadores censo,
            EducacionalDashboardResponse.FluxoEscolar fluxo,
            EducacionalDashboardResponse.InfraestruturaEscolar infra,
            EducacionalDashboardResponse.EnemSuperior enem,
            EducacionalDashboardResponse.FndeRepasses fnde,
            EducacionalDashboardResponse.ContextoSocioeconomico contexto) {
        
        List<EducacionalDashboardResponse.Cruzamento> insights = new ArrayList<>();

        // Analfabetismo
        if (pnad.taxaAnalfabetismo() != null && pnad.taxaAnalfabetismo() > 8.0) {
            insights.add(new EducacionalDashboardResponse.Cruzamento(
                    "Alerta de Analfabetismo",
                    "A taxa de analfabetismo de " + pnad.taxaAnalfabetismo() + "% está acima da média.",
                    "ALTA"
            ));
        }

        // Relação Aluno/Professor
        if (censo.totalMatriculas() != null && censo.totalDocentes() != null && censo.totalDocentes() > 0) {
            double relacao = (double) censo.totalMatriculas() / censo.totalDocentes();
            if (relacao > 35) {
                insights.add(new EducacionalDashboardResponse.Cruzamento("Sobrecarga Docente", String.format("Alta relação aluno/professor: %.1f", relacao), "ALTA"));
            } else {
                insights.add(new EducacionalDashboardResponse.Cruzamento("Relação Aluno/Professor", String.format("Relação adequada: %.1f", relacao), "BAIXA"));
            }
        }

        // Evasão vs Repasses
        if (fluxo.taxaEvasao() != null && fluxo.taxaEvasao() > 10.0) {
            String msg = "Evasão alta (" + fluxo.taxaEvasao() + "%). ";
            if (fnde.valorPnate() < 1000000) {
                msg += "Possível correlação com baixos repasses de transporte (PNATE).";
                insights.add(new EducacionalDashboardResponse.Cruzamento("Risco de Evasão por Transporte", msg, "ALTA"));
            } else {
                insights.add(new EducacionalDashboardResponse.Cruzamento("Alerta de Evasão", msg, "MEDIA"));
            }
        }

        // Infraestrutura vs IDEB
        if (censo.idebMedio() != null && infra.percentualLaboratorios() != null) {
            if (censo.idebMedio() < 4.5 && infra.percentualLaboratorios() < 40.0) {
                insights.add(new EducacionalDashboardResponse.Cruzamento(
                        "Déficit Estrutural",
                        "IDEB baixo (%.1f) e carência de laboratórios (%.1f%%). Investimentos no PDDE são urgentes.".formatted(censo.idebMedio(), infra.percentualLaboratorios()),
                        "ALTA"
                ));
            }
        }

        // Nem-Nem
        if (contexto.taxaJovensNemNem() != null && contexto.taxaJovensNemNem() > 20.0) {
            insights.add(new EducacionalDashboardResponse.Cruzamento(
                    "Geração Nem-Nem Alta",
                    "Mais de 20% dos jovens da região não trabalham nem estudam, indicando vulnerabilidade no ingresso ao Ensino Superior ou mercado de trabalho.",
                    "MEDIA"
            ));
        }

        return insights;
    }
}
