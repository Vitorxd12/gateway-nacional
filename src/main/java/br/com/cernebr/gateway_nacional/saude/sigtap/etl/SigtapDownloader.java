package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import br.com.cernebr.gateway_nacional.saude.sigtap.config.SigtapProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Downloader do pacote mensal do SIGTAP.
 *
 * <p>O DataSUS disponibiliza o pacote por dois canais:</p>
 * <ul>
 *   <li><b>HTTP</b> (página de download) — depende de scraping de link
 *       que muda a cada competência.</li>
 *   <li><b>FTP</b> — endereçamento previsível pelo template
 *       {@code TabelaUnificada_{competencia}_v{revisao}.zip}. Preferimos
 *       FTP por estabilidade e cacheabilidade do path.</li>
 * </ul>
 *
 * <p>O {@link URLConnection} aceita ambos os esquemas; aqui resolvemos o
 * URI configurável e baixamos o stream direto para o disco de trabalho.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapDownloader {

    private static final DateTimeFormatter COMPETENCIA = DateTimeFormatter.ofPattern("yyyyMM");

    private final SigtapProperties props;

    public SigtapDownloader(SigtapProperties props) {
        this.props = props;
    }

    public String competenciaAtual() {
        return LocalDate.now().format(COMPETENCIA);
    }

    /**
     * Resolve o URL final substituindo placeholders {competencia} e {revisao}.
     * Retorna o caminho local do .zip baixado.
     */
    public Path baixarPacote(String competencia, String revisao, Path destDir) {
        Files.exists(destDir);
        try {
            Files.createDirectories(destDir);
        } catch (IOException ex) {
            throw new SigtapEtlException("Falha ao criar diretório de trabalho: " + destDir, ex);
        }

        String url = props.download().pacoteUrlTemplate()
                .replace("{competencia}", competencia)
                .replace("{revisao}", revisao);
        Path destFile = destDir.resolve("TabelaUnificada_" + competencia + "_v" + revisao + ".zip");

        log.info("[SIGTAP] Baixando pacote competencia={} revisao={} url={}", competencia, revisao, url);

        try (InputStream in = openUrlStream(URI.create(url).toURL());
             OutputStream out = Files.newOutputStream(destFile)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            log.info("[SIGTAP] Download concluído: {} bytes em {}", total, destFile);
            return destFile;
        } catch (IOException ex) {
            throw new SigtapEtlException("Falha de I/O no download SIGTAP: " + ex.getMessage(), ex);
        }
    }

    private InputStream openUrlStream(URL url) throws IOException {
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(props.download().timeoutMs());
        conn.setReadTimeout(props.download().timeoutMs());
        conn.setRequestProperty("User-Agent", "gateway-nacional/1.0 (+sigtap-etl)");
        return conn.getInputStream();
    }
}
