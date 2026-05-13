package br.com.cernebr.gateway_nacional.insights.dossie.service;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.service.CnpjService;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjResponse;
import br.com.cernebr.gateway_nacional.insights.dossie.dto.DossieCorporativoResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DossieCorporativoService {

    private final CnpjService cnpjService;

    public DossieCorporativoService(CnpjService cnpjService) {
        this.cnpjService = cnpjService;
    }

    public DossieCorporativoResponse gerarDossie(String cnpj) {
        CnpjResponse dadosCadastrais = cnpjService.findByCnpj(cnpj);
        
        // TODO: Integrar com scraping real do Simples Nacional
        var simples = new DossieCorporativoResponse.SimplesNacionalInfo(false, null);
        
        // TODO: Integrar com Portal da Transparência (CEIS/CNEP)
        var restricoes = List.<DossieCorporativoResponse.RestricaoCguInfo>of();
        
        // TODO: Integrar com modulo de Licitações buscando vitórias
        var vitorias = List.<DossieCorporativoResponse.LicitacaoVitoriaInfo>of();

        return new DossieCorporativoResponse(dadosCadastrais, simples, restricoes, vitorias);
    }
}
