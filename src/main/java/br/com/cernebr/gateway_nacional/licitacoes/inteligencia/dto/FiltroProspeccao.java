package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Filtro do cruzamento de prospecção (CRM). Combina as DUAS faces:
 *
 * <ul>
 *   <li><b>Edital</b>: {@code setor}, {@code municipioOrgaoIbge}, {@code ufOrgao},
 *       {@code modalidade}, janela {@code dataDe..dataAte}, {@code papel},
 *       {@code valorMin..valorMax}.</li>
 *   <li><b>Empresa</b>: {@code cnaeEmpresa} (prefixo), {@code ufEmpresa},
 *       {@code municipioEmpresaIbge}.</li>
 * </ul>
 *
 * <p>Todos os campos são opcionais (null = sem filtro). Resolve o caso de uso
 * "empresas que venceram licitações do setor X na cidade Y".</p>
 */
public record FiltroProspeccao(
        String setor,
        String municipioOrgaoIbge,
        String ufOrgao,
        String modalidade,
        String papel,
        OffsetDateTime dataDe,
        OffsetDateTime dataAte,
        BigDecimal valorMin,
        BigDecimal valorMax,
        String cnaeEmpresa,
        String ufEmpresa,
        String municipioEmpresaIbge,
        int page,
        int size
) {
}
