package br.com.cernebr.gateway_nacional.operacional.cptec.client;

import java.util.Map;

/**
 * Tabelas estáticas extraídas dos catálogos publicados pelo CPTEC/INPE.
 *
 * <p>Mantidas em memória (não cacheadas) porque mudam em escala geológica:
 * a sigla "ec = Encoberto com Chuvas Isoladas" não muda de release pra
 * release, e a tabela de UF→Região é federalmente estável. Sincronizadas
 * com {@code servicos.cptec.inpe.br/XML/#condicoes-tempo} (jan/2026).</p>
 */
public final class CptecCatalogos {

    private CptecCatalogos() {}

    public static final String BASE_URL_DEFAULT = "http://servicos.cptec.inpe.br/XML";

    public static final int MAX_DAYS = 6;

    public static final Map<String, String> CONDITION_DESCRIPTIONS = Map.<String, String>ofEntries(
            Map.entry("ec", "Encoberto com Chuvas Isoladas"),
            Map.entry("ci", "Chuvas Isoladas"),
            Map.entry("c", "Chuva"),
            Map.entry("in", "Instável"),
            Map.entry("pp", "Poss. de Pancadas de Chuva"),
            Map.entry("cm", "Chuva pela Manhã"),
            Map.entry("cn", "Chuva a Noite"),
            Map.entry("pt", "Pancadas de Chuva a Tarde"),
            Map.entry("pm", "Pancadas de Chuva pela Manhã"),
            Map.entry("np", "Nublado e Pancadas de Chuva"),
            Map.entry("pc", "Pancadas de Chuva"),
            Map.entry("pn", "Parcialmente Nublado"),
            Map.entry("cv", "Chuvisco"),
            Map.entry("ch", "Chuvoso"),
            Map.entry("t", "Tempestade"),
            Map.entry("ps", "Predomínio de Sol"),
            Map.entry("e", "Encoberto"),
            Map.entry("n", "Nublado"),
            Map.entry("cl", "Céu Claro"),
            Map.entry("nv", "Nevoeiro"),
            Map.entry("g", "Geada"),
            Map.entry("ne", "Neve"),
            Map.entry("nd", "Não Definido"),
            Map.entry("pnt", "Pancadas de Chuva a Noite"),
            Map.entry("psc", "Possibilidade de Chuva"),
            Map.entry("pcm", "Possibilidade de Chuva pela Manhã"),
            Map.entry("pct", "Possibilidade de Chuva a Tarde"),
            Map.entry("pcn", "Possibilidade de Chuva a Noite"),
            Map.entry("npt", "Nublado com Pancadas a Tarde"),
            Map.entry("npn", "Nublado com Pancadas a Noite"),
            Map.entry("ncn", "Nublado com Poss. de Chuva a Noite"),
            Map.entry("nct", "Nublado com Poss. de Chuva a Tarde"),
            Map.entry("ncm", "Nubl. c/ Poss. de Chuva pela Manhã"),
            Map.entry("npm", "Nublado com Pancadas pela Manhã"),
            Map.entry("npp", "Nublado com Possibilidade de Chuva"),
            Map.entry("vn", "Variação de Nebulosidade"),
            Map.entry("ct", "Chuva a Tarde"),
            Map.entry("ppn", "Poss. de Panc. de Chuva a Noite"),
            Map.entry("ppt", "Poss. de Panc. de Chuva a Tarde"),
            Map.entry("ppm", "Poss. de Panc. de Chuva pela Manhã")
    );

    public static final Map<String, String> WIND_DIRECTIONS = Map.ofEntries(
            Map.entry("N", "Norte"),
            Map.entry("S", "Sul"),
            Map.entry("L", "Leste"),
            Map.entry("E", "Leste"),
            Map.entry("O", "Oeste"),
            Map.entry("W", "Oeste"),
            Map.entry("NE", "Nordeste"),
            Map.entry("SE", "Sudeste"),
            Map.entry("NO", "Noroeste"),
            Map.entry("SO", "Sudoeste"),
            Map.entry("NNE", "Norte-nordeste"),
            Map.entry("ENE", "Lés-nordeste"),
            Map.entry("ESE", "Lés-sudeste"),
            Map.entry("SSE", "Sul-sudeste"),
            Map.entry("SSO", "Sul-sudoeste"),
            Map.entry("OSO", "Oés-sudoeste"),
            Map.entry("ONO", "Oés-noroeste"),
            Map.entry("NNO", "Nor-noroeste")
    );

    public static final Map<String, String> STATE_REGION = Map.ofEntries(
            Map.entry("AC", "Norte"),
            Map.entry("AL", "Nordeste"),
            Map.entry("AM", "Norte"),
            Map.entry("AP", "Norte"),
            Map.entry("BA", "Nordeste"),
            Map.entry("CE", "Nordeste"),
            Map.entry("DF", "Centro-Oeste"),
            Map.entry("ES", "Sudeste"),
            Map.entry("GO", "Centro-Oeste"),
            Map.entry("MA", "Nordeste"),
            Map.entry("MT", "Centro-Oeste"),
            Map.entry("MS", "Centro-Oeste"),
            Map.entry("MG", "Sudeste"),
            Map.entry("PA", "Norte"),
            Map.entry("PB", "Nordeste"),
            Map.entry("PR", "Sul"),
            Map.entry("PE", "Nordeste"),
            Map.entry("PI", "Nordeste"),
            Map.entry("RJ", "Sudeste"),
            Map.entry("RN", "Nordeste"),
            Map.entry("RS", "Sul"),
            Map.entry("RO", "Norte"),
            Map.entry("RR", "Norte"),
            Map.entry("SC", "Sul"),
            Map.entry("SP", "Sudeste"),
            Map.entry("SE", "Nordeste"),
            Map.entry("TO", "Norte")
    );

    public static String describeCondicao(String sigla) {
        if (sigla == null) return null;
        return CONDITION_DESCRIPTIONS.getOrDefault(sigla, sigla);
    }

    public static String describeVento(String sigla) {
        if (sigla == null) return null;
        return WIND_DIRECTIONS.getOrDefault(sigla.toUpperCase(), sigla);
    }

    public static String regiaoDe(String uf) {
        if (uf == null) return null;
        return STATE_REGION.get(uf.toUpperCase());
    }
}
