package br.com.cernebr.gateway_nacional.veicular.fipe.client;

import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeMarcaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTabelaReferenciaResponse;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeTipoVeiculo;
import br.com.cernebr.gateway_nacional.veicular.fipe.dto.FipeVeiculoResponse;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Contrato dos providers de <b>navegação</b> da FIPE — listar marcas, modelos
 * e tabelas-de-referência.
 *
 * <p>Distinto de {@link FipeClientProvider} (cotação) porque a cascata é
 * diferente: navegação roda em {@code [scraper, brasilApi]}, enquanto cotação
 * roda em {@code [scraper, brasilApi, parallelum]}. Parallelum não expõe
 * endpoints de navegação na free tier — manter as duas interfaces separadas
 * evita forçá-lo a implementar métodos {@code throw new UnsupportedOperation}.</p>
 *
 * <p>Quando {@code tabelaReferencia} é {@code null}, o provider deve usar a
 * tabela-de-referência mais recente (descoberta via
 * {@link #listTabelasReferencia()} ou cache interno).</p>
 */
public interface FipeNavegacaoProvider {

    List<FipeMarcaResponse> listMarcas(FipeTipoVeiculo tipo, @Nullable Integer tabelaReferencia);

    List<FipeVeiculoResponse> listVeiculosByMarca(FipeTipoVeiculo tipo,
                                                  String codigoMarca,
                                                  @Nullable Integer tabelaReferencia);

    List<FipeTabelaReferenciaResponse> listTabelasReferencia();

    String providerName();
}
