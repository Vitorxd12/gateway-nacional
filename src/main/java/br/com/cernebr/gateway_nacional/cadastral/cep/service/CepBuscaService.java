package br.com.cernebr.gateway_nacional.cadastral.cep.service;

import br.com.cernebr.gateway_nacional.cadastral.cep.client.NominatimReversoClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.client.ViaCepBuscaClient;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepBuscaResult;
import br.com.cernebr.gateway_nacional.cadastral.cep.dto.CepResponse;
import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orquestra as duas operações complementares à consulta por CEP:
 *
 * <ol>
 *   <li><b>Busca textual</b> ({@link #buscarPorEndereco}) — dado UF + cidade + logradouro,
 *       retorna lista de CEPs candidatos via ViaCEP. Útil para autocompletar campos de
 *       endereço em formulários.</li>
 *   <li><b>Geocodificação reversa</b> ({@link #buscarPorCoordenadas}) — dado lat/lon
 *       (ex.: clique num mapa), retorna o endereço e o CEP do ponto via Nominatim/OSM,
 *       enriquecido com IBGE em seguida. Útil para interfaces de seleção cartográfica.</li>
 * </ol>
 *
 * <h2>Pipeline da geocodificação reversa</h2>
 * <ol>
 *   <li>Nominatim resolve lat/lon → {@link CepResponse} bruto com cep + endereço.</li>
 *   <li>{@link IbgeEnrichmentService} tenta preencher o código IBGE em memória.</li>
 *   <li>O resultado (com ou sem IBGE) é devolvido ao caller.</li>
 * </ol>
 *
 * <p>Não há cache Redis neste service porque:
 * <ul>
 *   <li>Busca textual: os parâmetros são livres demais para uma chave razoável (usuários
 *       digitam parcialmente); cache local seria pouco efetivo.</li>
 *   <li>Geocodificação reversa: lat/lon são contínuos; cache por ponto exato teria taxa
 *       de hit próxima de zero. O Nominatim tem seus próprios caches internos.</li>
 * </ul>
 * Para casos de uso que reqeiram cache (ex.: mapa com alta frequência de cliques em
 * áreas pequenas), use cache no lado do cliente ou um proxy Redis com chave truncada.</p>
 */
@Slf4j
@Service
public class CepBuscaService {

    private final ViaCepBuscaClient viaCepBusca;
    private final NominatimReversoClient nominatimReverso;
    private final IbgeEnrichmentService ibgeEnrichmentService;

    public CepBuscaService(ViaCepBuscaClient viaCepBusca,
                           NominatimReversoClient nominatimReverso,
                           IbgeEnrichmentService ibgeEnrichmentService) {
        this.viaCepBusca = viaCepBusca;
        this.nominatimReverso = nominatimReverso;
        this.ibgeEnrichmentService = ibgeEnrichmentService;
    }

    // ── Busca textual ──────────────────────────────────────────────────────────

    /**
     * Consulta o ViaCEP buscando CEPs pelo endereço e aplica enriquecimento IBGE
     * em cada candidato retornado.
     *
     * @param uf         sigla da UF (ex.: "SP")
     * @param cidade     nome do município
     * @param logradouro nome do logradouro (mínimo 3 caracteres exigido pelo ViaCEP)
     * @return resultado com lista de candidatos enriquecidos
     * @throws ResourceUnavailableException quando o ViaCEP está indisponível
     */
    public CepBuscaResult buscarPorEndereco(String uf, String cidade, String logradouro) {
        log.debug("Busca textual de CEP: uf={} cidade={} logradouro={}", uf, cidade, logradouro);

        List<CepResponse> candidatos = viaCepBusca.buscarPorEndereco(uf, cidade, logradouro);

        // Enriquece IBGE em cada candidato (ViaCEP v1 já traz ibge, mas o
        // enriquecimento é no-op nesses casos — custo zero para o caso normal).
        List<CepResponse> enriquecidos = candidatos.stream()
                .map(ibgeEnrichmentService::enrich)
                .collect(Collectors.toList());

        log.debug("Busca textual retornou {} candidato(s) para logradouro={}",
                enriquecidos.size(), logradouro);
        return CepBuscaResult.of(enriquecidos);
    }

    // ── Geocodificação reversa ─────────────────────────────────────────────────

    /**
     * Resolve coordenadas geográficas para um endereço brasileiro com CEP.
     *
     * <p>Uso típico: usuário clica num ponto do mapa → JS passa lat/lon para este
     * endpoint → a UI exibe o CEP e o endereço correspondentes.</p>
     *
     * @param lat latitude WGS84 em graus decimais
     * @param lon longitude WGS84 em graus decimais
     * @return resultado com 0 ou 1 candidato (o Nominatim /reverse devolve um único resultado)
     * @throws ResourceUnavailableException quando o Nominatim está indisponível
     */
    public CepBuscaResult buscarPorCoordenadas(BigDecimal lat, BigDecimal lon) {
        log.debug("Geocodificação reversa: lat={} lon={}", lat, lon);

        Optional<CepResponse> hit = nominatimReverso.reverso(lat, lon);

        if (hit.isEmpty()) {
            log.debug("Nominatim reverso não encontrou endereço para lat={} lon={}", lat, lon);
            return CepBuscaResult.of(Collections.emptyList());
        }

        // Enriquece IBGE: Nominatim não devolve o código IBGE do município.
        CepResponse enriquecido = ibgeEnrichmentService.enrich(hit.get());
        log.debug("Geocodificação reversa resolvida: cep={}", enriquecido.cep());

        return CepBuscaResult.of(List.of(enriquecido));
    }
}
