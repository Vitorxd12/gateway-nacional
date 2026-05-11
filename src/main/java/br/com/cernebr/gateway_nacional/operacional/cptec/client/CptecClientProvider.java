package br.com.cernebr.gateway_nacional.operacional.cptec.client;

import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CidadeCptecResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.CondicaoAtualResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.OndasResponse;
import br.com.cernebr.gateway_nacional.operacional.cptec.dto.PrevisaoClimaResponse;

import java.util.List;

/**
 * Contrato comum entre os provedores CPTEC — fonte canônica (XML legado do
 * INPE) e proxy BrasilAPI. Cada método pode ser delegado para um provedor
 * diferente sob hedge; o orquestrador
 * ({@link br.com.cernebr.gateway_nacional.operacional.cptec.service.CptecService})
 * decide a estratégia (hedge paralelo vs. cascata).
 *
 * <p>Implementações lançam {@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException}
 * quando o upstream falha ou o Circuit Breaker dispara.</p>
 */
public interface CptecClientProvider {

    /** Pesquisa cidades pelo nome (substring). */
    List<CidadeCptecResponse> searchCidades(String nome);

    /** Condições atuais (METAR) para todas as capitais brasileiras. */
    List<CondicaoAtualResponse> condicoesCapitais();

    /** Condições atuais (METAR) para um aeroporto pelo código ICAO. */
    CondicaoAtualResponse condicoesAeroporto(String icao);

    /** Previsão climática para uma cidade (até {@code dias} dias, máx 6). */
    PrevisaoClimaResponse previsao(int cityCode, int dias);

    /**
     * Previsão climática semanal a partir de coordenadas geográficas.
     *
     * <p>O CPTEC resolve internamente a cidade do banco mais próxima ao
     * ponto informado, então o caller (frota, dispositivo IoT, drone, ERP
     * agro) não precisa carregar o {@code cityCode} numérico. Padrão de
     * 7 dias do upstream — o parâmetro {@code dias} apenas trunca a saída.</p>
     */
    PrevisaoClimaResponse previsaoSemana(double lat, double lon, int dias);

    /** Previsão de ondas e ventos marítimos (até {@code dias} dias). */
    OndasResponse ondas(int cityCode, int dias);

    String providerName();
}
