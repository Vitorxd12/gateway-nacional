package br.com.cernebr.gateway_nacional.insights.raiox.service;

import br.com.cernebr.gateway_nacional.cadastral.ibge.service.IbgeService;
import br.com.cernebr.gateway_nacional.cadastral.ibge.dto.MunicipioResponse;
import br.com.cernebr.gateway_nacional.saude.relatorios.service.TermometroApsService;
import br.com.cernebr.gateway_nacional.saude.relatorios.dto.RelatorioDesempenhoApsResponse;
import br.com.cernebr.gateway_nacional.saude.service.FnsService;
import br.com.cernebr.gateway_nacional.saude.dto.RepasseFnsResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.service.CptecService;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.PrevisaoClimaResponse;
import br.com.cernebr.gateway_nacional.insights.raiox.dto.RaioXMunicipalResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class RaioXMunicipalService {

    private final IbgeService ibgeService;
    private final TermometroApsService termometroApsService;
    private final FnsService fnsService;
    private final CptecService cptecService;

    public RaioXMunicipalService(IbgeService ibgeService, 
                                 TermometroApsService termometroApsService, 
                                 FnsService fnsService, 
                                 CptecService cptecService) {
        this.ibgeService = ibgeService;
        this.termometroApsService = termometroApsService;
        this.fnsService = fnsService;
        this.cptecService = cptecService;
    }

    public RaioXMunicipalResponse gerarRaioX(String ibge) {
        MunicipioResponse municipio = null;
        try {
            municipio = ibgeService.searchMunicipios(ibge).stream()
                .findFirst()
                .orElse(null);
        } catch (Exception e) {}

        RelatorioDesempenhoApsResponse saude = null;
        try {
            saude = termometroApsService.build(ibge, "202302"); // default fallback
        } catch (Exception e) {}

        List<RepasseFnsResponse> repasses = List.of();
        try {
            String comp = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
            repasses = fnsService.findRepasses(ibge, comp);
        } catch (Exception e) {}

        PrevisaoClimaResponse clima = null;
        if (municipio != null && municipio.nome() != null) {
            try {
                var cidades = cptecService.searchCidades(municipio.nome());
                if (!cidades.isEmpty()) {
                    clima = cptecService.previsao(cidades.get(0).id(), 4);
                }
            } catch (Exception e) {}
        }

        return new RaioXMunicipalResponse(municipio, saude, repasses, clima);
    }
}
