package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import br.com.cernebr.gateway_nacional.saude.sigtap.config.SigtapProperties;
import br.com.cernebr.gateway_nacional.saude.sigtap.fixture.SigtapFixtureLoader;
import br.com.cernebr.gateway_nacional.saude.sigtap.fixture.SigtapFixtureLoader.Fixture;
import br.com.cernebr.gateway_nacional.saude.sigtap.jdbc.SigtapJdbc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final int MIN_PROCEDIMENTOS_PARA_PROMOCAO = 1;

    private final SigtapJdbc jdbc;
    private final SigtapDownloader downloader;
    private final PositionalParser parser;
    private final SigtapFixtureLoader fixtureLoader;
    private final SigtapProperties props;
    private final TransactionTemplate tx;
    private final CacheManager cacheManager;

    public SigtapEtlService(SigtapJdbc jdbc,
                            SigtapDownloader downloader,
                            PositionalParser parser,
                            SigtapFixtureLoader fixtureLoader,
                            SigtapProperties props,
                            @Qualifier("sigtapTxTemplate") TransactionTemplate tx,
                            CacheManager cacheManager) {
        this.jdbc = jdbc;
        this.downloader = downloader;
        this.parser = parser;
        this.fixtureLoader = fixtureLoader;
        this.props = props;
        this.tx = tx;
        this.cacheManager = cacheManager;
    }

    /**
     * Ponto de entrada do scheduler. Verifica se já há ACTIVE para a
     * competência corrente; em caso afirmativo, no-op (idempotente).
     */
    public boolean executar() {
        jdbc.ensureSchema();
        String comp = downloader.competenciaAtual();
        if (jdbc.hasActiveForCompetencia(comp)) {
            log.info("[SIGTAP ETL] Competência {} já ativa — nada a fazer.", comp);
            return true;
        }
        return ingerirCompetencia(comp, "1");
    }

    /**
     * Carrega a competência informada (download real). Usado pelo
     * scheduler e por trigger manual.
     */
    public boolean ingerirCompetencia(String competencia, String revisao) {
        log.info("[SIGTAP ETL] Iniciando ingestão competência={} revisao={}", competencia, revisao);
        jdbc.ensureSchema();
        Path workDir = Path.of(props.etl().workDir());

        String sourceUrl = props.download().pacoteUrlTemplate()
                .replace("{competencia}", competencia)
                .replace("{revisao}", revisao);
        long datasetId = jdbc.createStagingDataset(competencia, revisao, sourceUrl);

        try {
            Path zip = downloader.baixarPacote(competencia, revisao, workDir);
            Path extracted = extrair(zip, workDir.resolve("ext-" + datasetId));
            parsearEInserir(datasetId, extracted, competencia);
            promover(datasetId, competencia);
            return true;
        } catch (Exception ex) {
            jdbc.markFailed(datasetId, "ETL falhou: " + ex.getMessage());
            log.error("[SIGTAP ETL] Ingestão competência={} falhou. Dataset {} marcado FAILED.",
                    competencia, datasetId, ex);
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
            promover(datasetId, f.competencia());
            return true;
        } catch (Exception ex) {
            jdbc.markFailed(datasetId, "Fixture ETL falhou: " + ex.getMessage());
            throw new SigtapEtlException("Falha ao carregar fixture: " + ex.getMessage(), ex);
        }
    }

    private Path extrair(Path zip, Path destDir) {
        try {
            Files.createDirectories(destDir);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path out = destDir.resolve(Path.of(entry.getName()).getFileName());
                    if (entry.isDirectory()) continue;
                    Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("[SIGTAP ETL] Pacote extraído em {}", destDir);
            return destDir;
        } catch (Exception ex) {
            throw new SigtapEtlException("Falha ao extrair zip SIGTAP: " + ex.getMessage(), ex);
        }
    }

    private void parsearEInserir(long datasetId, Path extracted, String competencia) {
        // Real DataSUS package: parse each positional .txt into batched inserts.
        // Aqui chamamos o parser por arquivo conhecido; o parser entrega Maps.
        // O mapeamento Map → record fica encapsulado em PositionalRowMapper.
        PositionalRowMapper mapper = new PositionalRowMapper(datasetId, competencia);

        parseFileIfExists(extracted, "tb_cbo.txt", PositionalLayout.CBO,
                row -> mapper.bufferCbo(row, jdbc));
        parseFileIfExists(extracted, "tb_cid.txt", PositionalLayout.CID,
                row -> mapper.bufferCid(row, jdbc));
        parseFileIfExists(extracted, "tb_procedimento.txt", PositionalLayout.PROCEDIMENTO,
                row -> mapper.bufferProcedimento(row, jdbc));
        parseFileIfExists(extracted, "rl_procedimento_cbo.txt", PositionalLayout.PROC_CBO,
                row -> mapper.bufferProcCbo(row, jdbc));
        parseFileIfExists(extracted, "rl_procedimento_cid.txt", PositionalLayout.PROC_CID,
                row -> mapper.bufferProcCid(row, jdbc));

        mapper.flushAll(jdbc);
    }

    private void parseFileIfExists(Path dir, String fileName, PositionalLayout layout,
                                   java.util.function.Consumer<java.util.Map<String, String>> handler) {
        Path file = dir.resolve(fileName);
        if (!Files.exists(file)) {
            log.warn("[SIGTAP ETL] Arquivo ausente no pacote: {} — pulando.", fileName);
            return;
        }
        try (java.io.InputStream in = Files.newInputStream(file)) {
            parser.parse(in, layout, handler);
        } catch (Exception ex) {
            throw new SigtapEtlException("Falha ao parsear " + fileName + ": " + ex.getMessage(), ex);
        }
    }

    private void promover(long datasetId, String competencia) {
        int total = jdbc.contarProcedimentos(datasetId);
        if (total < MIN_PROCEDIMENTOS_PARA_PROMOCAO) {
            jdbc.markFailed(datasetId, "Validação reprovou: apenas " + total + " procedimentos no dataset.");
            throw new SigtapEtlException("Validação reprovou: dataset com " + total +
                    " procedimentos (mínimo=" + MIN_PROCEDIMENTOS_PARA_PROMOCAO + ").");
        }

        tx.executeWithoutResult(status -> jdbc.promoteStagingToActive(datasetId, competencia));
        invalidarCache();
        log.info("[SIGTAP ETL] Dataset {} (competência {}) promovido para ACTIVE — {} procedimentos.",
                datasetId, competencia, total);
    }

    private void invalidarCache() {
        var cache = cacheManager.getCache(SIGTAP_CACHE);
        if (cache != null) {
            cache.clear();
            log.info("[SIGTAP ETL] Cache Redis '{}' invalidado.", SIGTAP_CACHE);
        }
    }
}
