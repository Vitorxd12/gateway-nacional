package br.com.cernebr.gateway_nacional.saude.sigtap.etl;

import br.com.cernebr.gateway_nacional.saude.sigtap.config.SigtapProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    private final SigtapLogService logService;

    public SigtapDownloader(SigtapProperties props, SigtapLogService logService) {
        this.props = props;
        this.logService = logService;
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

            java.util.List<FTPFile> matchedFiles = new java.util.ArrayList<>();
            for (FTPFile file : files) {
                if (file != null && file.getName() != null && FILENAME_PATTERN.matcher(file.getName()).matches()) {
                    matchedFiles.add(file);
                }
            }

            if (matchedFiles.isEmpty()) {
                throw new SigtapEtlException("Nenhum pacote SIGTAP reconhecido no diretório FTP: " + coords.dir());
            }

            // Ordena os pacotes por relevância (competência e revisão crescente)
            matchedFiles.sort((f1, f2) -> {
                Matcher m1 = FILENAME_PATTERN.matcher(f1.getName());
                Matcher m2 = FILENAME_PATTERN.matcher(f2.getName());
                m1.matches();
                m2.matches();
                String comp1 = m1.group(1);
                String rev1  = m1.group(2);
                String comp2 = m2.group(1);
                String rev2  = m2.group(2);
                int compCmp = comp1.compareTo(comp2);
                if (compCmp != 0) return compCmp;
                return Integer.compare(Integer.parseInt(rev1), Integer.parseInt(rev2));
            });

            // Seleciona os últimos 10 mais recentes para exibir nos logs
            int totalPacotes = matchedFiles.size();
            int startIndex = Math.max(0, totalPacotes - 10);
            
            logService.log("[SIGTAP ETL] --- ÚLTIMOS 10 PACOTES FTP DATASUS ---");
            for (int i = startIndex; i < totalPacotes; i++) {
                FTPFile file = matchedFiles.get(i);
                String sizeStr = String.format("%.2f MB", file.getSize() / 1024.0 / 1024.0);
                logService.log(String.format("[SIGTAP ETL] [%02d] %s (%s)", (i - startIndex + 1), file.getName(), sizeStr));
            }
            logService.log("[SIGTAP ETL] --------------------------------------------");

            // O pacote mais recente é o último da lista ordenada
            FTPFile latestFile = matchedFiles.get(totalPacotes - 1);
            Matcher m = FILENAME_PATTERN.matcher(latestFile.getName());
            m.matches();
            InfoPacote latest = new InfoPacote(m.group(1), m.group(2), latestFile.getName());

            logService.log("[SIGTAP ETL] Total de " + totalPacotes + " pacotes mapeados no FTP.");
            logService.log("[SIGTAP ETL] O pacote mais recente escolhido foi: " + latest.nomeArquivo());
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
            
            logService.log("[SIGTAP ETL] Conectado ao FTP. Preparando diretório local...");
            Files.createDirectories(destFile.getParent());
            
            logService.log("[SIGTAP ETL] Iniciando transferência de " + info.nomeArquivo());
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.setDataTimeout((int) Duration.ofSeconds(30).toMillis()); // 30s de timeout para dados
            
            logService.log("[SIGTAP ETL] DEBUG: Abrindo stream de rede...");
            try (OutputStream out = Files.newOutputStream(destFile);
                 InputStream in = ftp.retrieveFileStream(remoteFile)) {
                
                logService.log("[SIGTAP ETL] DEBUG: Stream de rede aberto com sucesso.");
                
                if (in == null) {
                    throw new SigtapEtlException("Não foi possível iniciar o stream de download para: " + remoteFile
                            + " — Resposta do servidor: " + ftp.getReplyString().trim());
                }

                byte[] buffer = new byte[32768]; // 32KB buffer
                int bytesRead;
                long totalDownloaded = 0;
                long lastLogAt = 0;
                
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalDownloaded += bytesRead;
                    
                    // Notifica a cada 1MB
                    if (totalDownloaded - lastLogAt >= 1024 * 1024) {
                        String progresso = String.format("%.1f", totalDownloaded / 1024.0 / 1024.0);
                        logService.log("[SIGTAP ETL] Progresso do download: " + progresso + " MB...");
                        lastLogAt = totalDownloaded;
                    }
                }
                
                // Importante para fechar a transação FTP corretamente
                if (!ftp.completePendingCommand()) {
                    throw new SigtapEtlException("Falha ao finalizar o comando de transferência no FTP.");
                }
            }

            long size = Files.size(destFile);
            String mbTotal = String.format("%.2f", size / 1024.0 / 1024.0);
            logService.log("[SIGTAP ETL] Download concluído com sucesso: " + mbTotal + " MB salvos.");
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
