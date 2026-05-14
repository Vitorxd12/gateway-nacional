package br.com.cernebr.gateway_nacional.cadastral.cnpj.client;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SocioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TelefoneDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TipoEstabelecimento;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Funções de normalização compartilhadas pelos clients de CNPJ.
 *
 * <p>Centralizar aqui evita 5 cópias divergentes do parser de capital social,
 * telefone, máscara LGPD e data ISO/BR.</p>
 */
@UtilityClass
public class CnpjParseSupport {

    public static final BigDecimal CENTAVOS_DIVISOR = new BigDecimal("100");
    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    public static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    public static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    /** Remove tudo que não for dígito. {@code null}-safe. */
    public static String digitsOnly(String value) {
        if (value == null) return null;
        String d = value.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    /** Padroniza maiúsculo + trim, devolvendo {@code null} para entrada vazia. */
    public static String upperTrim(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    public static String safeTrim(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Capital social entregue por providers que repassam o dump Serpro como
     * inteiro bruto (ex.: "120000000000" representando R$ 1.200.000.000,00).
     * Divide por 100 e fixa 2 casas para reais.
     */
    public static BigDecimal parseCapitalCentavosInteiros(Object raw) {
        if (raw == null) return null;
        try {
            BigDecimal value = (raw instanceof Number n)
                    ? new BigDecimal(n.toString())
                    : new BigDecimal(raw.toString().trim());
            return value.divide(CENTAVOS_DIVISOR, 2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Capital social entregue já em reais decimal (ex.: BrasilAPI v1 devolve
     * {@code "capital_social": 1200000000.00}). Apenas força 2 casas.
     */
    public static BigDecimal parseCapitalReais(Object raw) {
        if (raw == null) return null;
        try {
            BigDecimal value = (raw instanceof Number n)
                    ? new BigDecimal(n.toString())
                    : new BigDecimal(raw.toString().trim());
            return value.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try {
            if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(value, COMPACT);
            }
            if (value.contains("/")) {
                return LocalDate.parse(value, BR);
            }
            return LocalDate.parse(value, ISO);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Quebra telefone no formato {@code "DDD numero"} ou {@code "DDDNNNNNNNN"}. */
    public static TelefoneDTO splitTelefone(String raw) {
        String d = digitsOnly(raw);
        if (d == null || d.length() < 10) return null;
        if (d.length() == 12) {
            d = d.substring(d.length() - 11);
        }
        String ddd = d.substring(0, 2);
        String numero = d.substring(2);
        return new TelefoneDTO(ddd, numero);
    }

    /**
     * Máscara LGPD do documento do sócio.
     *
     * <p>CPF: {@code ***.NNN.NNN-**} | CNPJ: {@code **.NNN.NNN/NNNN-**}.</p>
     * <p>Se a entrada já estiver mascarada, devolve como está.</p>
     */
    public static String maskCpfCnpj(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.contains("*")) return value;
        String d = digitsOnly(value);
        if (d == null) return null;
        if (d.length() == 11) {
            return "***." + d.substring(3, 6) + "." + d.substring(6, 9) + "-**";
        }
        if (d.length() == 14) {
            return "**." + d.substring(2, 5) + "." + d.substring(5, 8) +
                    "/" + d.substring(8, 12) + "-**";
        }
        return value;
    }

    /** Trunca a lista de sócios ao limite duro do contrato canônico. */
    public static List<SocioDTO> capQsa(List<SocioDTO> qsa) {
        if (qsa == null || qsa.isEmpty()) return List.of();
        if (qsa.size() <= br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO.MAX_SOCIOS) {
            return qsa;
        }
        return qsa.subList(0, br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO.MAX_SOCIOS);
    }

    public static TipoEstabelecimento parseTipoEstabelecimento(String codigo, String text) {
        TipoEstabelecimento byCode = TipoEstabelecimento.fromCodigo(codigo);
        if (byCode != TipoEstabelecimento.DESCONHECIDO) return byCode;
        return TipoEstabelecimento.fromText(text);
    }
}
