package br.com.cernebr.gateway_nacional.cadastral.simples.client;

import br.com.cernebr.gateway_nacional.cadastral.simples.dto.SimplesNacionalResponse;

/**
 * Contrato comum para provedores de enquadramento Simples Nacional.
 *
 * <p>Implementações tipicamente caem em duas categorias:</p>
 * <ul>
 *   <li><b>Primário (scraper oficial):</b> realiza engenharia reversa do portal
 *       Consulta Optantes da Receita Federal (mantém sessão via cookies). Captura
 *       o estado vigente <em>e</em> o histórico de opções/exclusões.</li>
 *   <li><b>Fallback (provedor agregador):</b> reutiliza a integração ReceitaWS,
 *       que já entrega os campos {@code opcao_pelo_simples}, {@code data_opcao_pelo_simples},
 *       {@code opcao_pelo_mei} e {@code data_opcao_pelo_mei} no payload de CNPJ —
 *       cobre o cenário em que o portal oficial estiver com CAPTCHA ou
 *       indisponível, pagando a latência adicional do agregador.</li>
 * </ul>
 */
public interface SimplesNacionalClientProvider {

    SimplesNacionalResponse fetch(String cnpj);

    String providerName();
}
