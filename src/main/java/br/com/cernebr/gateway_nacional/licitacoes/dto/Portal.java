package br.com.cernebr.gateway_nacional.licitacoes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Locale;
import java.util.Optional;

/**
 * Portais de licitação suportados pelo Gateway GovTech.
 *
 * <p>O slug ({@link #slug()}) é a representação estável usada nas rotas
 * REST públicas ({@code /v1/licitacoes/{portal}/{identificador}}) e nas
 * tags de métricas. Mantemos o enum como contrato fechado para impedir
 * que portais não suportados vazem para o consumidor — adicionar um portal
 * novo exige uma entrada explícita aqui, no roteador do service e nos CBs.</p>
 */
@Schema(name = "Portal",
        description = "Portais de licitações cobertos pelo Gateway GovTech.")
public enum Portal {

    /** Portal Nacional de Compras Públicas — federal, hospedado pelo SERPRO. */
    COMPRASNET("comprasnet", "ComprasNet / PNCP"),

    /** Bolsa de Licitações e Leilões — concentra prefeituras e estatais. */
    BLL("bll", "Bolsa de Licitações e Leilões"),

    /** Bolsa Nacional de Compras — privada, com forte presença no Sul/Sudeste. */
    BNC("bnc", "Bolsa Nacional de Compras"),

    /** Licitanet — marketplace privado focado em pregão eletrônico. */
    LICITANET("licitanet", "Licitanet");

    private final String slug;
    private final String descricao;

    Portal(String slug, String descricao) {
        this.slug = slug;
        this.descricao = descricao;
    }

    @Schema(description = "Identificador estável usado nas rotas REST.", example = "comprasnet")
    public String slug() {
        return slug;
    }

    @Schema(description = "Nome humano do portal — exibido em respostas.", example = "ComprasNet / PNCP")
    public String descricao() {
        return descricao;
    }

    /**
     * Resolve um {@link Portal} pelo slug, sigla ou nome do enum. Tolerante
     * a case e a espaços. Devolve {@link Optional#empty()} para inputs que
     * não casam com nenhum portal suportado.
     */
    public static Optional<Portal> fromSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (Portal p : values()) {
            if (p.slug.equals(normalized) || p.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
