package br.com.cernebr.gateway_nacional.saude.sigtap.service;

import br.com.cernebr.gateway_nacional.saude.sigtap.dto.AuditoriaSigtapDTO;
import org.springframework.stereotype.Service;

@Service
public class SigtapService {

    public AuditoriaSigtapDTO auditarProcedimento(String ibge, String cbo, String sigtap) {
        boolean compativel = cbo.startsWith("225"); // Se for médico, compatível para o exemplo
        String justificativa = compativel ? "CBO Regulamentado para o procedimento" : "CBO incompatível com as regras do SIGTAP";
        return new AuditoriaSigtapDTO(ibge, cbo, sigtap, compativel, justificativa, "Cache Local SIGTAP");
    }
}
