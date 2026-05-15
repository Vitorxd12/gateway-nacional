package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import br.com.cernebr.gateway_nacional.saude.sigtap.config.SigtapProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloader do pacote mensal do SIGTAP via FTP passivo (PASV).
 *
 * <p>Usa {@link FTPClient} do Apache Commons Net com modo passivo explícito,
 * necessário para funcionar dentro de containers Docker onde o FTP ativo
 * (PORT) é bloqueado pelo NAT — o servidor tentaria abrir conexão de volta
 * para o container, mas o roteamento não permite.</p>
 *
 * <p>O DataSUS expõe o diretório em:
 * {@code ftp2.datasus.gov.br/pub/sistemas/tup/downloads/}.
 * A listagem via {@code LIST} retorna os nomes dos arquivos; filtramos
 * pelo padrão {@code TabelaUnificada_AAAAMM_vN.zip} e selecionamos
 * a competência e revisão mais recentes.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapDownloader {

    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("TabelaUnificada_(\\d{6})_v(\\d+)\\.zip");

    private final SigtapProperties props;

    public SigtapDownloader(SigtapProperties props) {
        this.props = props;
    }

    public record InfoPacote(String competencia, String revisao, String nomeArquivo) {}

    // ──────────────────────────────────────────────────────────────────
    //  Descoberta da versão mais recente no FTP
    // ──────────────────────────────────────────────────────────────────

    /**
     * Lista o diretório FTP do DataSUS e retorna o pacote mais recente
     * (maior competência e, dentro dela, maior revisão).
     */
    public InfoPacote buscarUltimoPacote() {
        FtpCoords coords = parseFtpUrl(props.download().pacoteUrlTemplate());
        log.info("[SIGTAP] Listando diretório FTP {}:{}{}", coords.host(), coords.port(), coords.dir());

        FTPClient ftp = new FTPClient();
        try {
            conectar(ftp, coords);

            FTPFile[] files = ftp.listFiles(coords.dir());
            if (files == null || files.length == 0) {
                throw new SigtapEtlException("Nenhum arquivo encontrado no diretório FTP: " + coords.dir());
            }

            InfoPacote latest = null;
            for (FTPFile file : files) {
                Matcher m = FILENAME_PATTERN.matcher(file.getName());
                if (m.matches()) {
                    String comp = m.group(1);
                    String rev  = m.group(2);
                    if (latest == null
                            || comp.compareTo(latest.competencia()) > 0
                            || (comp.equals(latest.competencia()) && rev.compareTo(latest.revisao()) > 0)) {
                        latest = new InfoPacote(comp, rev, file.getName());
                    }
                }
            }

            if (latest == null) {
                throw new SigtapEtlException("Nenhum pacote SIGTAP reconhecido no diretório FTP: " + coords.dir());
            }

            log.info("[SIGTAP] Último pacote encontrado: {}", latest.nomeArquivo());
            return latest;

        } catch (IOException ex) {
            throw new SigtapEtlException("Falha ao listar FTP SIGTAP: " + ex.getMessage(), ex);
        } finally {
            desconectar(ftp);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Download do pacote
    // ──────────────────────────────────────────────────────────────────

    /**
     * Baixa o pacote especificado para o diretório de destino.
     */
    public Path baixarPacote(InfoPacote info, Path destDir) {
        try {
            Files.createDirectories(destDir);
        } catch (IOException ex) {
            throw new SigtapEtlException("Falha ao criar diretório de trabalho: " + destDir, ex);
        }

        FtpCoords coords = parseFtpUrl(props.download().pacoteUrlTemplate());
        String remoteFile = coords.dir() + info.nomeArquivo();
        Path destFile = destDir.resolve(info.nomeArquivo());

        log.info("[SIGTAP] Baixando {} de ftp://{}:{}{}", info.nomeArquivo(), coords.host(), coords.port(), remoteFile);

        FTPClient ftp = new FTPClient();
        try {
            conectar(ftp, coords);
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            try (OutputStream out = Files.newOutputStream(destFile)) {
                boolean ok = ftp.retrieveFile(remoteFile, out);
                if (!ok) {
                    throw new SigtapEtlException("FTP retrieveFile falhou para: " + remoteFile
                            + " — reply: " + ftp.getReplyString().trim());
                }
            }

            long size = Files.size(destFile);
            log.info("[SIGTAP] Download concluído: {} bytes em {}", size, destFile);
            return destFile;

        } catch (IOException ex) {
            throw new SigtapEtlException("Falha de I/O no download SIGTAP: " + ex.getMessage(), ex);
        } finally {
            desconectar(ftp);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Helpers FTP
    // ──────────────────────────────────────────────────────────────────

    private record FtpCoords(String host, int port, String dir) {}

    /**
     * Extrai host, porta e diretório da URL template configurada.
     * Exemplo: ftp://ftp2.datasus.gov.br/pub/sistemas/tup/downloads/TabelaUnificada_{competencia}_v{revisao}.zip
     * → host=ftp2.datasus.gov.br, port=21, dir=/pub/sistemas/tup/downloads/
     */
    private FtpCoords parseFtpUrl(String template) {
        // Remove o nome do arquivo (último segmento após a última /)
        String withoutFile = template.substring(0, template.lastIndexOf('/') + 1);
        URI uri = URI.create(withoutFile);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 21;
        String dir = uri.getPath();
        if (!dir.endsWith("/")) dir += "/";
        return new FtpCoords(host, port, dir);
    }

    private void conectar(FTPClient ftp, FtpCoords coords) throws IOException {
        int timeout = props.download().timeoutMs();
        ftp.setConnectTimeout(timeout);
        ftp.setDefaultTimeout(timeout);
        ftp.setDataTimeout(timeout);

        ftp.connect(coords.host(), coords.port());
        ftp.login("anonymous", "gateway-nacional@datasus");

        // PASV — modo passivo: o cliente inicia AMBAS as conexões (controle e dados).
        // Obrigatório em ambientes com NAT/firewall (Docker, cloud, etc).
        ftp.enterLocalPassiveMode();

        log.debug("[SIGTAP] FTP conectado em {}:{} (modo passivo)", coords.host(), coords.port());
    }

    private void desconectar(FTPClient ftp) {
        try {
            if (ftp.isConnected()) {
                ftp.logout();
                ftp.disconnect();
            }
        } catch (IOException ex) {
            log.warn("[SIGTAP] Erro ao desconectar do FTP: {}", ex.getMessage());
        }
    }
}
