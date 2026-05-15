package br.com.cernebr.gateway_nacional.veicular.avaliacao.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Rota canônica KBB resolvida pelo Discovery Layer — tradução de um código
 * FIPE + ano para o trio {@code (slugMarca, slugModelo, slugVersao, kbbId)}
 * que o portal {@code kbb.com.br} exige para servir o blob {@code vehiclePrices}.
 *
 * <p>O KBB indexa veículos pelo seu próprio {@code KbbId} interno, não pelo
 * código FIPE. Antes do Discovery, o operador era responsável por traduzir
 * FIPE em slug+id manualmente — efetivamente impedindo que o scraper
 * funcionasse para qualquer veículo fora de um conjunto codificado. O
 * Discovery elimina esse acoplamento: dado FIPE + ano + dicas (marca / modelo),
 * o serviço descobre a URL exata.</p>
 *
 * <h2>Origem da rota</h2>
 * <ul>
 *   <li><b>SEED</b> — pré-carregada do {@code classpath:data/fipe_kbb_map.json}.
 *       Curadoria do operador, versionada no git.</li>
 *   <li><b>DYNAMIC</b> — descoberta em tempo real consultando o portal KBB
 *       via FlareSolverr + Jsoup (catálogo de marcas → lista de versões).</li>
 *   <li><b>RUNTIME</b> — restaurada do arquivo de cache local
 *       ({@code tmp/kbb-discovery-cache.json}) que persiste rotas DYNAMIC
 *       entre reinícios do processo, sem demandar Redis.</li>
 * </ul>
 *
 * @see br.com.cernebr.gateway_nacional.veicular.avaliacao.service.KbbDiscoveryService
 */
@Schema(name = "KbbRouteDTO",
        description = "Rota canônica do portal KBB para um veículo identificado por FIPE + ano.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KbbRouteDTO(
        @Schema(description = "Código FIPE de origem usado para indexar a rota.", example = "004275-7")
        String fipeCode,

        @Schema(description = "Identificador interno do veículo no portal KBB (segmento numérico da URL).",
                example = "28249")
        Integer kbbId,

        @Schema(description = "Marca slugificada (segmento {marca} da URL).", example = "chevrolet")
        String slugMarca,

        @Schema(description = "Modelo slugificado (segmento {modelo} da URL).", example = "onix")
        String slugModelo,

        @Schema(description = "Versão / trim slugificada (segmento {versao} da URL).",
                example = "lt-1-0-8v-mt-flex")
        String slugVersao,

        @Schema(description = "Categoria mercadológica do veículo no taxonomia KBB.",
                example = "hatchback",
                allowableValues = {"carro", "hatchback", "seda", "picape", "suv-crossover", "esportivo", "minivan"})
        String categoria,

        @Schema(description = "Ano modelo (4 dígitos). Compõe a chave de cache junto com o FIPE.",
                example = "2018")
        Integer anoModelo,

        @Schema(description = "URL-template canônica com o placeholder {canal} aberto — o scraper substitui por 'troca' ou 'particular' para resolver os dois canais.",
                example = "https://kbb.com.br/sp/2018/hatchback/chevrolet/onix/lt-1-0-8v-mt-flex/28249/preco-de-{canal}/")
        String urlTemplate,

        @Schema(description = "Origem da rota: SEED (curadoria do operador), DYNAMIC (descoberta em tempo real via portal), RUNTIME (recuperada do cache persistido em disco).",
                example = "DYNAMIC",
                allowableValues = {"SEED", "DYNAMIC", "RUNTIME"})
        String source,

        @Schema(description = "Timestamp ISO-8601 da descoberta (ou da carga do seed).",
                example = "2026-05-15T13:42:11Z")
        Instant discoveredAt
) {

    /** Resolve a URL final do canal solicitado a partir do template descoberto. */
    public String channelUrl(String canal) {
        if (urlTemplate == null) return null;
        return urlTemplate.replace("{canal}", canal);
    }

    /** Atalho — a URL "guarda-chuva" exposta no DTO de auditoria aponta para particular. */
    public String urlReferencia() {
        return channelUrl("particular");
    }

    /** Cria uma cópia com {@code source} reposicionado — usado ao promover SEED→RUNTIME. */
    public KbbRouteDTO withSource(String newSource) {
        return new KbbRouteDTO(fipeCode, kbbId, slugMarca, slugModelo, slugVersao,
                categoria, anoModelo, urlTemplate, newSource, discoveredAt);
    }
}
