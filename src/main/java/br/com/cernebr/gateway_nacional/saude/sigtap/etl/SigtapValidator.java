package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import br.com.cernebr.gateway_nacional.saude.sigtap.jdbc.SigtapJdbc;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Procedimento;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Validador de integridade pós-ingestão (Sanity Checks).
 *
 * <p>Antes de promover um dataset de STAGING para ACTIVE, executamos estas
 * validações para garantir que o layout do DataSUS não mudou silenciosamente
 * e que não estamos injetando lixo no sistema hospitalar.</p>
 */
@Slf4j
@Component
public class SigtapValidator {

    private static final int MIN_PROCEDIMENTOS_ESPERADOS = 4000;
    private static final String CODIGO_PATTERN = "\\d{10}";
    
    private final SigtapLogService logService;

    public SigtapValidator(SigtapLogService logService) {
        this.logService = logService;
    }

    public void validar(long datasetId, SigtapJdbc jdbc) {
        logService.log("[SIGTAP ETL] Iniciando validação de integridade (Sanity Checks)...");

        // 1. Validação de Volume (Contagem total)
        int total = jdbc.contarProcedimentos(datasetId);
        if (total < MIN_PROCEDIMENTOS_ESPERADOS) {
            throw new SigtapEtlException(String.format(
                    "Volume de dados insuficiente: %d procedimentos encontrados (mínimo esperado: %d). " +
                    "Possível arquivo corrompido ou incompleto no FTP.", total, MIN_PROCEDIMENTOS_ESPERADOS));
        }

        // Buscamos uma amostra para validações de campo
        List<Procedimento> amostra = jdbc.buscarProcedimentos(datasetId, "", 100);

        // 2. Validação de Schema (Formato dos códigos)
        boolean codigosInvalidos = amostra.stream()
                .anyMatch(p -> p.codigo() == null || !p.codigo().matches(CODIGO_PATTERN));

        if (codigosInvalidos) {
            throw new SigtapEtlException("Falha de Schema: códigos de procedimento fora do padrão (10 dígitos). " +
                    "O layout do DataSUS pode ter mudado.");
        }

        // 3. Validação de Conteúdo (Nomes vazios)
        boolean nomesVazios = amostra.stream()
                .anyMatch(p -> p.nome() == null || p.nome().isBlank() || p.nome().length() < 5);

        if (nomesVazios) {
            throw new SigtapEtlException("Falha de Conteúdo: nomes de procedimentos ausentes ou truncados na amostra.");
        }

        // 4. Validação Financeira (Check de valores zerados em massa)
        // Se pegarmos 100 itens e NENHUM tiver valor maior que zero, algo está muito errado.
        boolean todosZerados = amostra.stream()
                .allMatch(p -> isZero(p.valorSa()) && isZero(p.valorSh()) && isZero(p.valorSp()));

        if (todosZerados) {
            log.warn("[SIGTAP Validator] ALERTA: Todos os procedimentos da amostra possuem valor R$ 0,00.");
            // Não bloqueamos obrigatoriamente aqui pois alguns meses podem ter correções específicas,
            // mas logamos o alerta crítico.
        }

        logService.log("[SIGTAP ETL] Validação concluída: " + total + " procedimentos aprovados.");
    }

    private boolean isZero(BigDecimal v) {
        return v == null || v.compareTo(BigDecimal.ZERO) == 0;
    }
}
