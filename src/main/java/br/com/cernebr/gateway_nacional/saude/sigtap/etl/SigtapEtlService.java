package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import br.com.cernebr.gateway_nacional.saude.sigtap.config.SigtapProperties;
import br.com.cernebr.gateway_nacional.saude.sigtap.fixture.SigtapFixtureLoader;
import br.com.cernebr.gateway_nacional.saude.sigtap.fixture.SigtapFixtureLoader.Fixture;
import br.com.cernebr.gateway_nacional.saude.sigtap.jdbc.SigtapJdbc;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Dataset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static br.com.cernebr.gateway_nacional.config.CacheConfig.SIGTAP_CACHE;

/**
 * Orquestrador do ETL SIGTAP.
 *
 * <p><b>Estratégia Blue-Green embarcada no SQLite:</b></p>
 * <ol>
 *   <li>{@link SigtapJdbc#ensureSchema()} — DDL idempotente
 *       ({@code CREATE TABLE IF NOT EXISTS}) executado pelo próprio
 *       ETL antes da primeira escrita. Sem migrations globais.</li>
 *   <li>Aloca uma nova linha em {@code sigtap_dataset} com {@code status=STAGING}.</li>
 *   <li>Baixa o pacote do DataSUS (ou usa o fixture embarcado), descompacta,
 *       parseia os .txt posicionais e insere em lote nas tabelas com
 *       o {@code dataset_id} da staging.</li>
 *   <li>Valida a integridade (contagem mínima de procedimentos).</li>
 *   <li>Em UMA transação SQLite: arquiva o ACTIVE anterior e promove a
 *       staging para ACTIVE. Atômico — nenhum leitor vê estado parcial.</li>
 *   <li>Invalida o cache Redis {@code sigtap} para que próximos hits
 *       consultem a nova competência.</li>
 * </ol>
 *
 * <p>Em falha catastrófica antes da promoção, o dataset fica marcado como
 * {@code FAILED} — o ACTIVE anterior continua íntegro servindo a API.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapEtlService {

    private final SigtapJdbc jdbc;
    private final SigtapDownloader downloader;
    private final PositionalParser parser;
    private final SigtapFixtureLoader fixtureLoader;
    private final SigtapProperties props;
    private final TransactionTemplate tx;
    private final CacheManager cacheManager;
    private final SigtapValidator validator;
    private final SigtapLogService logService;

    public SigtapEtlService(SigtapJdbc jdbc,
                            SigtapDownloader downloader,
                            PositionalParser parser,
                            SigtapFixtureLoader fixtureLoader,
                            SigtapProperties props,
                            @Qualifier("sigtapTxTemplate") TransactionTemplate tx,
                            CacheManager cacheManager,
                            SigtapValidator validator,
                            SigtapLogService logService) {
        this.jdbc = jdbc;
        this.downloader = downloader;
        this.parser = parser;
        this.fixtureLoader = fixtureLoader;
        this.props = props;
        this.tx = tx;
        this.cacheManager = cacheManager;
        this.validator = validator;
        this.logService = logService;
    }

    private void logProgress(String msg) {
        logService.log(msg);
    }

    public List<String> getRecentLogs() {
        return logService.getLogs();
    }

    public List<String> getCurrentRunLogs() {
        return logService.getCurrentRunLogs();
    }

    /**
     * Ponto de entrada do scheduler. Busca o pacote mais recente no FTP
     * do DataSUS e o ingere se ainda não for o ACTIVE atual.
     */
    public boolean executar() {
        logService.clear();
        try {
            jdbc.ensureSchema();
            logProgress("[SIGTAP ETL] Buscando último pacote no FTP...");
            SigtapDownloader.InfoPacote info = downloader.buscarUltimoPacote();

            logProgress("[SIGTAP ETL] Verificando versão ativa no banco local...");
            Optional<Dataset> active = jdbc.findActive();
            
            if (active.isPresent() && info.revisao().equals(active.get().revisao())) {
                logProgress(String.format("[SIGTAP ETL] AVISO: O sistema já está operando com a versão mais recente (%s v%s).",
                        info.competencia(), info.revisao()));
                logProgress("[SIGTAP ETL] Nenhuma ação necessária. Download abortado.");
                return true;
            }

            if (active.isPresent()) {
                logProgress(String.format("[SIGTAP ETL] Nova revisão encontrada para %s: v%s (atual: v%s)",
                        info.competencia(), info.revisao(), active.get().revisao()));
            } else {
                logProgress(String.format("[SIGTAP ETL] Nenhuma base ativa encontrada. Iniciando ingestão da %s v%s...",
                        info.competencia(), info.revisao()));
            }

            return ingerirPacote(info);
        } catch (Throwable t) {
            String errorMsg = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
            logProgress("[SIGTAP ETL] ERRO FATAL: " + errorMsg);
            log.error("Falha catastrófica no ETL SIGTAP", t);
            return false;
        }
    }

    /**
     * Ingestão direta de um InfoPacote (descoberto via FTP).
     * O ZIP é salvo em disco e mantido após a ingestão bem-sucedida.
     * O ZIP da competência anterior (é apagado somente após a promoção).
     * Em caso de falha, o ZIP novo é apagado e o antigo permanece.
     */
    public boolean ingerirPacote(SigtapDownloader.InfoPacote info) {
        logProgress("[SIGTAP ETL] Iniciando ingestão: " + info.nomeArquivo());
        jdbc.ensureSchema();
        Path workDir = Path.of(props.etl().workDir());

        String baseUrl = props.download().pacoteUrlTemplate()
                .substring(0, props.download().pacoteUrlTemplate().lastIndexOf("/") + 1);
        String sourceUrl = baseUrl + info.nomeArquivo();

        // Captura o ZIP antigo (ACTIVE atual) antes de criar o staging
        Optional<Dataset> previousActive = jdbc.findActive();
        Path previousZip = previousActive.map(d -> zipPath(workDir, d)).orElse(null);

        long datasetId = jdbc.createStagingDataset(info.competencia(), info.revisao(), sourceUrl);
        Path newZip = null;

        try {
            logProgress("[SIGTAP ETL] PASSO 1/4: Baixando pacote oficial...");
            newZip = downloader.baixarPacote(info, workDir);
            
            logProgress("[SIGTAP ETL] PASSO 2/4: Extraindo arquivos posicionais...");
            Path extracted = extrair(newZip, workDir.resolve("ext-" + datasetId));
            
            logProgress("[SIGTAP ETL] PASSO 3/4: Processando tabelas e cruzamentos...");
            parsearEInserir(datasetId, extracted, info.competencia());
            
            logProgress("[SIGTAP ETL] PASSO 4/4: Finalizando e promovendo base...");
            limparDirExtracted(extracted);
            promover(datasetId, info.competencia(), previousZip);
            return true;
        } catch (Exception ex) {
            // AVISO PRIMEIRO NO TERMINAL (Garantia de visibilidade)
            logProgress("[SIGTAP ETL] ERRO CRÍTICO: " + ex.getMessage());
            
            // Depois tenta gravar no banco
            try {
                jdbc.markFailed(datasetId, "ETL falhou: " + ex.getMessage());
            } catch (Exception dbEx) {
                logProgress("[SIGTAP ETL] AVISO: Não foi possível registrar a falha no banco: " + dbEx.getMessage());
            }

            if (newZip != null) {
                deleteSilently(newZip, "ZIP novo (rollback)");
            }
            throw ex instanceof SigtapEtlException se ? se : new SigtapEtlException(ex.getMessage(), ex);
        }
    }


    /**
     * Carrega o fixture embarcado como ACTIVE. Útil em primeiro boot
     * antes do primeiro download real do DataSUS.
     */
    public boolean ingerirFixture() {
        log.info("[SIGTAP ETL] Carregando fixture embarcado (modo seed)");
        jdbc.ensureSchema();
        Fixture f = fixtureLoader.load();
        long datasetId = jdbc.createStagingDataset(f.competencia(), f.revisao(),
                "fixture://" + f.fonte());
        try {
            jdbc.batchInsertCbos(datasetId, fixtureLoader.toCbos(datasetId, f));
            jdbc.batchInsertCids(datasetId, fixtureLoader.toCids(datasetId, f));
            jdbc.batchInsertProcedimentos(datasetId, fixtureLoader.toProcedimentos(datasetId, f));
            jdbc.batchInsertProcCbo(datasetId, fixtureLoader.toProcCbo(datasetId, f));
            jdbc.batchInsertProcCid(datasetId, fixtureLoader.toProcCid(datasetId, f));
            promover(datasetId, f.competencia(), null); // fixture não tem ZIP anterior em disco
            return true;
        } catch (Exception ex) {
            jdbc.markFailed(datasetId, "Fixture ETL falhou: " + ex.getMessage());
            throw new SigtapEtlException("Falha ao carregar fixture: " + ex.getMessage(), ex);
        }
    }

    private Path extrair(Path zip, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            logProgress("[SIGTAP ETL] Extraindo pacote: " + zip.getFileName());
            
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                int filesCount = 0;
                while ((entry = zis.getNextEntry()) != null) {
                    Path filePath = targetDir.resolve(entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(filePath);
                    } else {
                        Files.createDirectories(filePath.getParent());
                        Files.copy(zis, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        filesCount++;
                        if (filesCount % 5 == 0) logProgress("[SIGTAP ETL] ... extraídos " + filesCount + " arquivos");
                    }
                    zis.closeEntry();
                }
                logProgress("[SIGTAP ETL] Extração finalizada: " + filesCount + " arquivos processados.");
            }
            return targetDir;
        } catch (java.io.IOException ex) {
            throw new SigtapEtlException("Falha ao extrair zip SIGTAP: " + ex.getMessage(), ex);
        }
    }

    private void parsearEInserir(long datasetId, Path extracted, String competencia) {
        PositionalRowMapper mapper = new PositionalRowMapper(datasetId, competencia);

        logProgress("[SIGTAP ETL] -> Processando ocupações (CBO)...");
        parseFileIfExists(extracted, "tb_cbo.txt", PositionalLayout.CBO,
                row -> mapper.bufferCbo(row, jdbc));
        
        logProgress("[SIGTAP ETL] -> Processando patologias (CID-10)...");
        parseFileIfExists(extracted, "tb_cid.txt", PositionalLayout.CID,
                row -> mapper.bufferCid(row, jdbc));
        
        logProgress("[SIGTAP ETL] -> Processando procedimentos...");
        parseFileIfExists(extracted, "tb_procedimento.txt", PositionalLayout.PROCEDIMENTO,
                row -> mapper.bufferProcedimento(row, jdbc));
        
        logProgress("[SIGTAP ETL] -> Processando regras de Ocupação/Procedimento...");
        parseFileIfExists(extracted, "rl_procedimento_cbo.txt", PositionalLayout.PROC_CBO,
                row -> mapper.bufferProcCbo(row, jdbc));
        
        logProgress("[SIGTAP ETL] -> Processando regras de CID/Procedimento...");
        parseFileIfExists(extracted, "rl_procedimento_cid.txt", PositionalLayout.PROC_CID,
                row -> mapper.bufferProcCid(row, jdbc));

        logProgress("[SIGTAP ETL] Gravando dados no SQLite (lote final)...");
        mapper.flushAll(jdbc);
        logProgress("[SIGTAP ETL] OK: Todas as tabelas foram migradas para o banco.");
    }

    private void parseFileIfExists(Path dir, String fileName, PositionalLayout layout,
                                   java.util.function.Consumer<java.util.Map<String, String>> handler) {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) {
            logProgress("[SIGTAP ETL] INFO: Arquivo " + fileName + " não encontrado (ignorado).");
            return;
        }
        
        try {
            logProgress("[SIGTAP ETL] Processando " + fileName + "...");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                parser.parse(in, layout, handler);
            }
            logProgress("[SIGTAP ETL] OK: " + fileName + " processado.");
        } catch (Exception ex) {
            String msg = (ex.getCause() != null) ? ex.getMessage() + " (Causa: " + ex.getCause().getMessage() + ")" : ex.getMessage();
            throw new SigtapEtlException("Falha ao parsear " + fileName + ": " + msg, ex);
        }
    }

    private Path zipPath(Path workDir, Dataset d) {
        return workDir.resolve("TabelaUnificada_" + d.competencia() + "_v" + d.revisao() + ".zip");
    }

    private void limparDirExtracted(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                          .forEach(p -> deleteSilently(p, "arquivo extraído"));
                }
            }
        } catch (Exception ex) {
            log.warn("[SIGTAP ETL] Falha ao limpar diretório extraído {}: {}", dir, ex.getMessage());
        }
    }

    private void deleteSilently(Path path, String label) {
        try {
            Files.deleteIfExists(path);
            log.debug("[SIGTAP ETL] Removido {}: {}", label, path);
        } catch (Exception ex) {
            log.warn("[SIGTAP ETL] Não foi possível remover {} {}: {}", label, path, ex.getMessage());
        }
    }

    private void promover(long datasetId, String competencia, Path previousZip) {
        logProgress("[SIGTAP ETL] Validando integridade do dataset " + datasetId + "...");
        // Sanity Checks via Validator
        validator.validar(datasetId, jdbc);

        logProgress("[SIGTAP ETL] Aprovado. Promovendo para ACTIVE...");
        tx.executeWithoutResult(status -> jdbc.promoteStagingToActive(datasetId, competencia));
        invalidarCache();
        logProgress("[SIGTAP ETL] Sucesso! Dataset " + datasetId + " promovido.");

        // Apaga o ZIP antigo somente após promoção atômica bem-sucedida
        if (previousZip != null) {
            deleteSilently(previousZip, "ZIP antigo (pós-promoção)");
        }
    }

    private void invalidarCache() {
        var cache = cacheManager.getCache(SIGTAP_CACHE);
        if (cache != null) {
            cache.clear();
            log.info("[SIGTAP ETL] Cache Redis '{}' invalidado.", SIGTAP_CACHE);
        }
    }
}
