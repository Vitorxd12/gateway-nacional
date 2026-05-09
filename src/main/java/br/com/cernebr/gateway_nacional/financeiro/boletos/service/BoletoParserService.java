package br.com.cernebr.gateway_nacional.financeiro.boletos.service;

import br.com.cernebr.gateway_nacional.financeiro.boletos.dto.BoletoResponse;
import br.com.cernebr.gateway_nacional.financeiro.boletos.enums.TipoBoleto;
import br.com.cernebr.gateway_nacional.financeiro.boletos.exception.BoletoInvalidoException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Motor algorítmico determinístico que valida e extrai dados de linhas
 * digitáveis FEBRABAN. Sem rede, sem cache, sem Redis — todo o trabalho
 * acontece em CPU em complexidade linear no comprimento da linha
 * (constante: 47 ou 48 dígitos).
 *
 * <h2>Layouts suportados</h2>
 *
 * <h3>Boleto bancário (47 dígitos na linha digitável)</h3>
 * <p>Cinco campos: 10, 11, 11, 1, 14. Conversão para os 44 dígitos do
 * código de barras conforme tabela oficial FEBRABAN (capítulo 8.1 do
 * layout padrão de cobrança). Cada um dos campos 1-3 carrega seu próprio
 * DAC mod-10 (multiplicadores 2,1,2,1... da direita para esquerda, soma
 * de dígitos quando produto > 9). O DAC geral do código de barras é
 * mod-11 com multiplicadores 2..9 cíclicos.</p>
 *
 * <h3>Guia de arrecadação (48 dígitos na linha digitável)</h3>
 * <p>Quatro blocos de 12 dígitos (11 dados + 1 DAC). O 3º dígito do
 * código de barras determina o módulo de validação:
 * {@code 6, 7 → mod-10}; {@code 8, 9 → mod-11}. Cada bloco da linha
 * tem o próprio DAC, e ainda há um DAC geral na posição 4 do barcode
 * cobrindo as outras 43 posições. Vencimento não é padronizado em
 * arrecadação (varia por segmento/órgão), então o response devolve
 * {@code dataVencimento=null}.</p>
 *
 * <h2>Fator de Vencimento — regra de virada FEBRABAN</h2>
 * <p>A FEBRABAN definiu o fator como {@code dias entre 07/10/1997 e a
 * data de vencimento}. O fator 1000 marcou 03/07/2000, e o fator 9999
 * caiu em 21/02/2025 — a partir daí o campo de 4 dígitos esgota. A
 * Comunicação FEBRABAN reiniciou o contador: 22/02/2025 voltou a ser
 * fator 1000.</p>
 *
 * <p>Como a linha digitável <i>não</i> carrega data de emissão, a mesma
 * fator pode mapear para uma data legada (base 07/10/1997) ou para uma
 * data pós-virada (base 22/02/2025). O parser calcula ambos os candidatos
 * e devolve o que está mais próximo de hoje — heurística que casa com a
 * realidade operacional: nenhum sistema parseia boletos emitidos há mais
 * de uma década, então a ambiguidade só apareceria em arquivos legados.</p>
 */
@Slf4j
@Service
public class BoletoParserService {

    private static final String DOMAIN = "boletos";
    private static final String PROVIDER = "febraban-parser";
    private static final String METRIC_REQUESTS = "gateway.provider.requests";
    private static final String METRIC_LATENCY = "gateway.provider.latency";

    /** Fator 1000 corresponde a 03/07/2000 nesta base — convencionalmente expressa como "dias desde 07/10/1997". */
    private static final LocalDate FATOR_BASE_LEGACY = LocalDate.of(1997, 10, 7);

    /** Reinício do fator: 22/02/2025 voltou a ser fator 1000 após o esgotamento dos 4 dígitos em 21/02/2025. */
    private static final LocalDate FATOR_BASE_NOVO = LocalDate.of(2025, 2, 22);

    private final MeterRegistry meterRegistry;

    public BoletoParserService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public BoletoResponse parse(String rawInput) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            BoletoResponse response = doParse(rawInput);
            recordOutcome("success", sample);
            log.info("Boleto parsed tipo={} banco={} valor={} venc={}",
                    response.tipo(), response.bancoEmissor(), response.valor(), response.dataVencimento());
            return response;
        } catch (BoletoInvalidoException ex) {
            recordOutcome("invalid", sample);
            log.warn("Boleto rejeitado: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            recordOutcome("failure", sample);
            throw ex;
        }
    }

    private BoletoResponse doParse(String rawInput) {
        if (rawInput == null) {
            throw new BoletoInvalidoException("Linha digitável obrigatória.");
        }
        String digits = rawInput.replaceAll("\\D", "");
        return switch (digits.length()) {
            case 47 -> parseBancario(digits);
            case 48 -> parseArrecadacao(digits);
            // Quando o cliente já passou o código de barras (44 dígitos) — caso útil pra integrações
            // que escaneiam a barra direto. Identificação pelo identificador FEBRABAN (pos 0):
            // '8' = arrecadação; qualquer outro = bancário.
            case 44 -> digits.charAt(0) == '8' ? parseArrecadacaoBarcode(digits) : parseBancarioBarcode(digits);
            default -> throw new BoletoInvalidoException(
                    "Linha digitável deve ter 47 (bancário) ou 48 dígitos (arrecadação), ou 44 (código de barras). Recebido: " + digits.length() + " dígitos.");
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // Boleto bancário
    // ─────────────────────────────────────────────────────────────────────

    private BoletoResponse parseBancario(String linha) {
        validateBancarioFieldDvs(linha);
        String barcode = linhaToBarcodeBancario(linha);
        validateBancarioBarcodeDv(barcode);
        return buildBancarioResponse(linha, barcode);
    }

    private BoletoResponse parseBancarioBarcode(String barcode) {
        validateBancarioBarcodeDv(barcode);
        // Reconstrói linha apenas para devolver no response (não revalidamos os campos).
        String linha = barcodeToLinhaBancario(barcode);
        return buildBancarioResponse(linha, barcode);
    }

    private BoletoResponse buildBancarioResponse(String linha, String barcode) {
        String banco = barcode.substring(0, 3);
        int fator = Integer.parseInt(barcode.substring(5, 9));
        String valorStr = barcode.substring(9, 19);
        BigDecimal valor = new BigDecimal(valorStr).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
        LocalDate venc = calcularVencimento(fator);
        return new BoletoResponse(linha, barcode, TipoBoleto.BANCARIO, banco, valor, venc, true);
    }

    /**
     * Cinco campos da linha digitável (47): pos 0-9 (10) | 10-20 (11) | 21-31 (11) | 32 (1) | 33-46 (14).
     * Os campos 1-3 carregam DAC mod-10 na última posição. O campo 4 é o DAC geral mod-11 do barcode
     * (validado depois, em {@link #validateBancarioBarcodeDv}).
     */
    private void validateBancarioFieldDvs(String linha) {
        validateMod10Field("Campo 1", linha.substring(0, 9), linha.charAt(9));
        validateMod10Field("Campo 2", linha.substring(10, 20), linha.charAt(20));
        validateMod10Field("Campo 3", linha.substring(21, 31), linha.charAt(31));
    }

    private void validateMod10Field(String label, String data, char dvChar) {
        int expected = mod10(data);
        int actual = dvChar - '0';
        if (expected != actual) {
            throw new BoletoInvalidoException(
                    label + ": DV mod-10 inválido (esperado " + expected + ", recebido " + actual + ").");
        }
    }

    /**
     * Conversão linha digitável (47) → código de barras (44), conforme FEBRABAN:
     * <pre>
     * Barcode pos 0-2  ← Linha pos 0-2     (banco)
     * Barcode pos 3    ← Linha pos 3       (moeda)
     * Barcode pos 4    ← Linha pos 32      (DV geral, do campo 4)
     * Barcode pos 5-8  ← Linha pos 33-36   (fator de vencimento)
     * Barcode pos 9-18 ← Linha pos 37-46   (valor)
     * Barcode pos 19-23 ← Linha pos 4-8    (campo 1, dígitos 5-9 — pula DV em pos 9)
     * Barcode pos 24-33 ← Linha pos 10-19  (campo 2, dígitos 1-10 — pula DV em pos 20)
     * Barcode pos 34-43 ← Linha pos 21-30  (campo 3, dígitos 1-10 — pula DV em pos 31)
     * </pre>
     */
    private String linhaToBarcodeBancario(String linha) {
        return new StringBuilder(44)
                .append(linha, 0, 4)         // banco + moeda
                .append(linha.charAt(32))    // DV geral
                .append(linha, 33, 47)       // fator + valor
                .append(linha, 4, 9)         // campo 1 chars 5-9
                .append(linha, 10, 20)       // campo 2 chars 1-10
                .append(linha, 21, 31)       // campo 3 chars 1-10
                .toString();
    }

    /** Inverso de {@link #linhaToBarcodeBancario}: usado quando o cliente envia direto o barcode. */
    private String barcodeToLinhaBancario(String bc) {
        // Campo 1: bc[0-3] + bc[19-23] + DV mod10
        String campo1Data = bc.substring(0, 4) + bc.substring(19, 24);
        String campo1 = campo1Data + mod10(campo1Data);
        // Campo 2: bc[24-33] + DV mod10
        String campo2Data = bc.substring(24, 34);
        String campo2 = campo2Data + mod10(campo2Data);
        // Campo 3: bc[34-43] + DV mod10
        String campo3Data = bc.substring(34, 44);
        String campo3 = campo3Data + mod10(campo3Data);
        // Campo 4: DV geral barcode
        String campo4 = String.valueOf(bc.charAt(4));
        // Campo 5: fator + valor
        String campo5 = bc.substring(5, 19);
        return campo1 + campo2 + campo3 + campo4 + campo5;
    }

    /**
     * DV geral do barcode bancário: módulo 11 das 43 posições não-DV (pos 0-3 + pos 5-43),
     * multiplicadores 2..9 cíclicos da direita para esquerda. Resíduo 0/10/11 → DV = 1.
     */
    private void validateBancarioBarcodeDv(String barcode) {
        if (barcode.length() != 44) {
            throw new BoletoInvalidoException("Código de barras bancário deve ter 44 dígitos.");
        }
        String forDv = barcode.substring(0, 4) + barcode.substring(5);
        int expected = mod11Bancario(forDv);
        int actual = barcode.charAt(4) - '0';
        if (expected != actual) {
            throw new BoletoInvalidoException(
                    "DV geral do código de barras inválido (mod-11 esperado " + expected
                            + ", recebido " + actual + ").");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Guia de arrecadação
    // ─────────────────────────────────────────────────────────────────────

    private BoletoResponse parseArrecadacao(String linha) {
        String barcode = linhaToBarcodeArrecadacao(linha);
        validateArrecadacaoBarcode(barcode);
        validateArrecadacaoBlocosDv(linha, barcode.charAt(2));
        return buildArrecadacaoResponse(linha, barcode);
    }

    private BoletoResponse parseArrecadacaoBarcode(String barcode) {
        validateArrecadacaoBarcode(barcode);
        String linha = barcodeToLinhaArrecadacao(barcode);
        return buildArrecadacaoResponse(linha, barcode);
    }

    private BoletoResponse buildArrecadacaoResponse(String linha, String barcode) {
        String valorStr = barcode.substring(4, 15);
        BigDecimal valor = new BigDecimal(valorStr).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
        return new BoletoResponse(linha, barcode, TipoBoleto.ARRECADACAO, null, valor, null, true);
    }

    /**
     * Concatena os 11 dígitos de dados de cada um dos 4 blocos de 12 da linha.
     * <pre>
     * Barcode pos 0-10  ← Linha pos 0-10
     * Barcode pos 11-21 ← Linha pos 12-22
     * Barcode pos 22-32 ← Linha pos 24-34
     * Barcode pos 33-43 ← Linha pos 36-46
     * </pre>
     */
    private String linhaToBarcodeArrecadacao(String linha) {
        return new StringBuilder(44)
                .append(linha, 0, 11)
                .append(linha, 12, 23)
                .append(linha, 24, 35)
                .append(linha, 36, 47)
                .toString();
    }

    private String barcodeToLinhaArrecadacao(String bc) {
        char modIdent = bc.charAt(2);
        StringBuilder linha = new StringBuilder(48);
        appendBlocoArrecadacao(linha, bc.substring(0, 11), modIdent);
        appendBlocoArrecadacao(linha, bc.substring(11, 22), modIdent);
        appendBlocoArrecadacao(linha, bc.substring(22, 33), modIdent);
        appendBlocoArrecadacao(linha, bc.substring(33, 44), modIdent);
        return linha.toString();
    }

    private void appendBlocoArrecadacao(StringBuilder linha, String data, char modIdent) {
        int dv = isMod10Arrecadacao(modIdent) ? mod10(data) : mod11Arrecadacao(data);
        linha.append(data).append(dv);
    }

    private void validateArrecadacaoBarcode(String barcode) {
        if (barcode.length() != 44) {
            throw new BoletoInvalidoException("Código de barras de arrecadação deve ter 44 dígitos.");
        }
        if (barcode.charAt(0) != '8') {
            throw new BoletoInvalidoException(
                    "Identificador de produto inválido em arrecadação (pos 1 deve ser '8', recebido '"
                            + barcode.charAt(0) + "').");
        }
        char modIdent = barcode.charAt(2);
        if (!isMod10Arrecadacao(modIdent) && !isMod11Arrecadacao(modIdent)) {
            throw new BoletoInvalidoException(
                    "Identificador de valor inválido (pos 3 deve ser 6/7 para mod-10 ou 8/9 para mod-11; recebido '"
                            + modIdent + "').");
        }
        // DV geral cobre as 43 outras posições.
        String forDv = barcode.substring(0, 3) + barcode.substring(4);
        int expected = isMod10Arrecadacao(modIdent) ? mod10(forDv) : mod11Arrecadacao(forDv);
        int actual = barcode.charAt(3) - '0';
        if (expected != actual) {
            throw new BoletoInvalidoException(
                    "DV geral da arrecadação inválido (esperado " + expected + ", recebido " + actual
                            + ", módulo " + (isMod10Arrecadacao(modIdent) ? "10" : "11") + ").");
        }
    }

    private void validateArrecadacaoBlocosDv(String linha, char modIdent) {
        boolean mod10 = isMod10Arrecadacao(modIdent);
        validateBlocoDv("Bloco 1", linha.substring(0, 11),  linha.charAt(11), mod10);
        validateBlocoDv("Bloco 2", linha.substring(12, 23), linha.charAt(23), mod10);
        validateBlocoDv("Bloco 3", linha.substring(24, 35), linha.charAt(35), mod10);
        validateBlocoDv("Bloco 4", linha.substring(36, 47), linha.charAt(47), mod10);
    }

    private void validateBlocoDv(String label, String data, char dvChar, boolean mod10) {
        int expected = mod10 ? mod10(data) : mod11Arrecadacao(data);
        int actual = dvChar - '0';
        if (expected != actual) {
            throw new BoletoInvalidoException(
                    label + ": DV " + (mod10 ? "mod-10" : "mod-11")
                            + " inválido (esperado " + expected + ", recebido " + actual + ").");
        }
    }

    private static boolean isMod10Arrecadacao(char ident) { return ident == '6' || ident == '7'; }
    private static boolean isMod11Arrecadacao(char ident) { return ident == '8' || ident == '9'; }

    // ─────────────────────────────────────────────────────────────────────
    // Módulos FEBRABAN
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Módulo 10 FEBRABAN: multiplicadores alternados 2,1,2,1... da direita
     * para esquerda; quando o produto excede 9, soma os dígitos (ex.: 14 → 5).
     * DAC = 10 - (soma mod 10); resíduo 0 → DAC = 0.
     */
    static int mod10(String digits) {
        int sum = 0;
        int multiplier = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int product = (digits.charAt(i) - '0') * multiplier;
            if (product > 9) {
                product = (product / 10) + (product % 10);
            }
            sum += product;
            multiplier = (multiplier == 2) ? 1 : 2;
        }
        int rem = sum % 10;
        return (rem == 0) ? 0 : 10 - rem;
    }

    /**
     * Módulo 11 do DV geral de boleto bancário: multiplicadores 2..9 cíclicos
     * da direita para esquerda; DAC = 11 - (soma mod 11); resíduo 0/10/11 → DAC = 1.
     */
    static int mod11Bancario(String digits) {
        int sum = sumMod11(digits);
        int dac = 11 - (sum % 11);
        return (dac == 0 || dac == 10 || dac == 11) ? 1 : dac;
    }

    /**
     * Módulo 11 de arrecadação: mesma soma do bancário, mas o tratamento de
     * resíduo é diferente — resíduo 0 ou 1 → DAC = 0; resíduo 10 → DAC = 1.
     */
    static int mod11Arrecadacao(String digits) {
        int rem = sumMod11(digits) % 11;
        if (rem == 0 || rem == 1) return 0;
        if (rem == 10) return 1;
        return 11 - rem;
    }

    private static int sumMod11(String digits) {
        int sum = 0;
        int multiplier = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sum += (digits.charAt(i) - '0') * multiplier;
            multiplier = (multiplier == 9) ? 2 : multiplier + 1;
        }
        return sum;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fator de vencimento — regra de virada FEBRABAN
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Resolve o fator de vencimento (4 dígitos) numa data, lidando com a
     * virada FEBRABAN de 22/02/2025. Quando ambos os candidatos (base
     * legacy 1997-10-07 e base nova 2025-02-22) são plausíveis, escolhe o
     * que está mais próximo de hoje — heurística que casa com a realidade
     * operacional (boletos parseados são quase sempre recentes).
     *
     * <p>Fator 0 é convenção FEBRABAN para "boleto sem vencimento" e
     * retorna {@code null}.</p>
     */
    LocalDate calcularVencimento(int fator) {
        if (fator == 0) return null;
        LocalDate candidatoLegacy = FATOR_BASE_LEGACY.plusDays(fator);
        LocalDate candidatoNovo = FATOR_BASE_NOVO.plusDays((long) fator - 1000L);
        LocalDate hoje = LocalDate.now();
        long distLegacy = Math.abs(ChronoUnit.DAYS.between(candidatoLegacy, hoje));
        long distNovo = Math.abs(ChronoUnit.DAYS.between(candidatoNovo, hoje));
        return distLegacy <= distNovo ? candidatoLegacy : candidatoNovo;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Métricas
    // ─────────────────────────────────────────────────────────────────────

    private void recordOutcome(String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder(METRIC_LATENCY)
                .tag("domain", DOMAIN)
                .tag("provider", PROVIDER)
                .register(meterRegistry));
        meterRegistry.counter(METRIC_REQUESTS,
                "domain", DOMAIN,
                "provider", PROVIDER,
                "outcome", outcome).increment();
    }
}
