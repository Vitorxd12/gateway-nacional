package br.com.cernebr.gateway_nacional.cadastral.cep.client;

import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse.Localizacao;

import java.util.Optional;

/**
 * Contrato dos provedores de geocodificação de CEP — distinto de
 * {@link CepClientProvider} (que resolve a base do endereço) porque
 * latitude/longitude são uma camada de enriquecimento separada com seus
 * próprios fallbacks e SLA de degradação.
 *
 * <p><b>Semântica do retorno:</b></p>
 * <ul>
 *   <li>{@link Optional#empty()} — o provider respondeu mas não conseguiu
 *       geocodificar este endereço (ex.: zona rural sem mapeamento OSM).
 *       O orquestrador cascateia para o próximo provider; falha final é
 *       silenciosa (CepResponse devolvido sem {@code localizacao}).</li>
 *   <li>{@link Optional#of(Object)} — coordenadas resolvidas com sucesso.</li>
 *   <li>{@link br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException} —
 *       provider/CB indisponível. O orquestrador trata como falha de
 *       tentativa e continua o hedge.</li>
 * </ul>
 *
 * <p><b>Por que tier separado:</b> a falha de geo não pode derrubar a
 * resposta de CEP — endereço é o core do contrato; localização é
 * enriquecimento. Manter em interface separada deixa explícito que o
 * tratamento de erros é diferente.</p>
 */
public interface GeoCepClientProvider {

    /**
     * Resolve coordenadas WGS84 a partir do endereço já hidratado pelo
     * tier 1 (campos UF, localidade, logradouro e CEP devem estar populados
     * para Nominatim filtrar com precisão).
     */
    Optional<Localizacao> geocodificar(CepResponse base);

    String providerName();
}
