package br.com.cernebr.gateway_nacional.insights.raiox.dto;

import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;
import br.com.cernebr.gateway_nacional.saude.relatorios.dto.RelatorioDesempenhoApsResponse;
import br.com.cernebr.gateway_nacional.saude.dto.RepasseFnsResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.PrevisaoClimaResponse;
import java.util.List;

public record RaioXMunicipalResponse(
    MunicipioResponse dadosDemograficos,
    RelatorioDesempenhoApsResponse saudeAps,
    List<RepasseFnsResponse> repassesFns,
    PrevisaoClimaResponse clima
) {}
