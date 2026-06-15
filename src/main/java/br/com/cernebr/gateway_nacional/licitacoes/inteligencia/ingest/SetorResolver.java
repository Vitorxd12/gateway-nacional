package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Mapeia atividade econômica para um macro-setor de mercado, nas DUAS faces do
 * cruzamento:
 *
 * <ul>
 *   <li>{@link #fromCnae} — setor da EMPRESA pela divisão CNAE (2 primeiros
 *       dígitos). É o "ramo de atuação" pedido nos filtros de inteligência.</li>
 *   <li>{@link #fromObjeto} — setor do EDITAL por heurística de palavra-chave no
 *       objeto. PNCP não traz CNAE no edital; até existir classificação melhor,
 *       inferimos do texto. <b>MVP</b> — refinar com catálogo CATMAT/CATSER
 *       depois.</li>
 * </ul>
 *
 * <p>Os rótulos batem com o filtro do caso de uso CRM ("setor de Educação").</p>
 */
@Component
public class SetorResolver {

    public static final String OUTROS = "OUTROS";

    // Divisão CNAE (2 dígitos) → macro-setor. Cobre os ramos mais frequentes em
    // licitações públicas; o resto cai em OUTROS.
    private static final Map<String, String> SETOR_POR_DIVISAO = Map.ofEntries(
            Map.entry("85", "EDUCACAO"),
            Map.entry("86", "SAUDE"), Map.entry("87", "SAUDE"), Map.entry("88", "SAUDE"),
            Map.entry("21", "SAUDE"), Map.entry("75", "SAUDE"),
            Map.entry("41", "CONSTRUCAO"), Map.entry("42", "CONSTRUCAO"), Map.entry("43", "CONSTRUCAO"),
            Map.entry("45", "COMERCIO"), Map.entry("46", "COMERCIO"), Map.entry("47", "COMERCIO"),
            Map.entry("62", "TECNOLOGIA"), Map.entry("63", "TECNOLOGIA"),
            Map.entry("49", "TRANSPORTE"), Map.entry("50", "TRANSPORTE"), Map.entry("51", "TRANSPORTE"),
            Map.entry("52", "TRANSPORTE"), Map.entry("53", "TRANSPORTE"),
            Map.entry("55", "ALIMENTACAO"), Map.entry("56", "ALIMENTACAO"),
            Map.entry("84", "ADMINISTRACAO_PUBLICA")
    );

    // Palavra-chave (normalizada, sem acento) → setor do edital. Ordem importa:
    // a primeira que casa no objeto vence.
    private static final Map<String, String> SETOR_POR_PALAVRA = Map.ofEntries(
            Map.entry("educac", "EDUCACAO"), Map.entry("escola", "EDUCACAO"),
            Map.entry("ensino", "EDUCACAO"), Map.entry("merenda", "EDUCACAO"),
            Map.entry("saude", "SAUDE"), Map.entry("hospital", "SAUDE"),
            Map.entry("medic", "SAUDE"), Map.entry("farmac", "SAUDE"), Map.entry("odontolog", "SAUDE"),
            Map.entry("obra", "CONSTRUCAO"), Map.entry("pavimenta", "CONSTRUCAO"),
            Map.entry("reforma", "CONSTRUCAO"), Map.entry("construc", "CONSTRUCAO"),
            Map.entry("software", "TECNOLOGIA"), Map.entry("informatica", "TECNOLOGIA"),
            Map.entry("tecnologia da informacao", "TECNOLOGIA"),
            Map.entry("transporte", "TRANSPORTE"), Map.entry("veiculo", "TRANSPORTE"),
            Map.entry("aliment", "ALIMENTACAO"), Map.entry("refeic", "ALIMENTACAO")
    );

    /** Setor da empresa a partir do CNAE (subclasse 7 dígitos). null → não resolvido. */
    public String fromCnae(String cnae) {
        if (cnae == null || cnae.length() < 2) {
            return null;
        }
        return SETOR_POR_DIVISAO.getOrDefault(cnae.substring(0, 2), OUTROS);
    }

    /** Setor do edital por palavra-chave no objeto. OUTROS quando nada casa. */
    public String fromObjeto(String objeto) {
        if (objeto == null || objeto.isBlank()) {
            return OUTROS;
        }
        String n = normalize(objeto);
        for (Map.Entry<String, String> e : SETOR_POR_PALAVRA.entrySet()) {
            if (n.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return OUTROS;
    }

    private static String normalize(String value) {
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase(Locale.ROOT);
    }
}
