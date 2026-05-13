package br.com.cernebr.gateway_nacional.insights.dossie.dto;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjResponse;
import java.util.List;

public record DossieCorporativoResponse(
    CnpjResponse dadosCadastrais,
    SimplesNacionalInfo simplesNacional,
    List<RestricaoCguInfo> restricoesCgu,
    List<LicitacaoVitoriaInfo> licitacoesVencidas
) {
    public record SimplesNacionalInfo(boolean optante, String dataOpcao) {}
    public record RestricaoCguInfo(String fonte, String tipoRestricao, String data) {}
    public record LicitacaoVitoriaInfo(String portal, String orgao, String valorEstimado, String data) {}
}
