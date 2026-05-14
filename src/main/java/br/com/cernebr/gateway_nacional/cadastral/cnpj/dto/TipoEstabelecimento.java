package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TipoEstabelecimento",
        description = "Tipo do estabelecimento conforme a base RFB (1=MATRIZ, 2=FILIAL).")
public enum TipoEstabelecimento {
    MATRIZ,
    FILIAL,
    DESCONHECIDO;

    public static TipoEstabelecimento fromCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return DESCONHECIDO;
        }
        return switch (codigo.trim()) {
            case "1" -> MATRIZ;
            case "2" -> FILIAL;
            default -> DESCONHECIDO;
        };
    }

    public static TipoEstabelecimento fromText(String text) {
        if (text == null || text.isBlank()) {
            return DESCONHECIDO;
        }
        String upper = text.trim().toUpperCase();
        if (upper.startsWith("MATRIZ")) return MATRIZ;
        if (upper.startsWith("FILIAL")) return FILIAL;
        return DESCONHECIDO;
    }
}
