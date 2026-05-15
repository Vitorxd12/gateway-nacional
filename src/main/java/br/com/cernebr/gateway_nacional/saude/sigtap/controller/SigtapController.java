package br.com.cernebr.gateway_nacional.saude.sigtap.controller;

import br.com.cernebr.gateway_nacional.saude.sigtap.dto.AuditoriaSigtapDTO;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapCboResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapCidResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapExportResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapProcedimentoResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapStatusResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.dto.SigtapValorResumoResponse;
import br.com.cernebr.gateway_nacional.saude.sigtap.service.SigtapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/saude/sigtap")
@Tag(
        name = "SIGTAP / DataSUS",
        description = "Dicionário canônico relacional do SUS — procedimentos, CBOs autorizados, CID-10 justificantes e valores SA/SH/SP. Armazenamento em SQLite embarcado com ingestão Blue-Green pelo cron noturno."
)
public class SigtapController {

    private final SigtapService service;

    public SigtapController(SigtapService service) {
        this.service = service;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Status detalhado do motor SIGTAP",
            description = "Retorna configurações, dados da base ativa, detalhes da última tentativa de sincronização e histórico recente de datasets.")
    public SigtapStatusResponse status() {
        return service.status();
    }

    @PostMapping(value = "/atualizar", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Força a atualização imediata do SIGTAP",
            description = "Aciona o motor de busca automática no FTP do DataSUS e realiza a ingestão se houver nova competência ou revisão disponível.")
    public SigtapStatusResponse atualizar() {
        return service.atualizar();
    }

    @GetMapping(value = "/download", produces = "application/zip")
    @Operation(summary = "Baixa o pacote .zip original da competência ativa",
            description = "Serve o arquivo compactado que foi baixado do DataSUS. O ZIP é mantido em disco e substituído apenas quando uma nova competência é promovida com sucesso.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "ZIP disponível para download"),
            @ApiResponse(responseCode = "404", description = "ZIP ainda não disponível — execute POST /atualizar primeiro",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Módulo desativado ou base não carregada",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Resource> download() {
        Path path = service.getArquivoZipAtual();
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }


    @GetMapping(value = "/procedimentos/{codigo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Detalhes completos do procedimento por código SIGTAP")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Procedimento encontrado",
                    content = @Content(schema = @Schema(implementation = SigtapProcedimentoResponse.class))),
            @ApiResponse(responseCode = "404", description = "Código não existe na competência ativa",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Módulo desativado ou base não carregada",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public SigtapProcedimentoResponse procedimento(
            @Parameter(description = "Código SIGTAP (10 dígitos)", example = "0201010042", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{10}", message = "Código SIGTAP deve conter exatamente 10 dígitos.")
            String codigo
    ) {
        return service.procedimento(codigo);
    }

    @GetMapping(value = "/procedimentos", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Busca textual otimizada por código ou nome do procedimento",
            description = "Termo aceita substring case-insensitive sobre o nome ou prefixo numérico do código. Retorna até 100 resultados ordenados por nome.")
    public List<SigtapProcedimentoResponse> buscarProcedimentos(
            @Parameter(description = "Termo de busca", example = "atendimento medico", required = true)
            @RequestParam("busca") String busca,
            @Parameter(description = "Limite de resultados (1..500)", example = "100")
            @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        return service.buscarProcedimentos(busca, Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping(value = "/cbo/{codigoCbo}/procedimentos", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cruzamento direto: tudo que este CBO está autorizado a faturar")
    public List<SigtapProcedimentoResponse> procedimentosDoCbo(
            @Parameter(description = "Código CBO (6 dígitos)", example = "225125", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{6}", message = "Código CBO deve conter exatamente 6 dígitos.")
            String codigoCbo
    ) {
        return service.procedimentosDoCbo(codigoCbo);
    }

    @GetMapping(value = "/procedimentos/{codigo}/cbo", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cruzamento inverso: CBOs exigidos para faturar o procedimento")
    public List<SigtapCboResponse> cbosDoProcedimento(
            @PathVariable
            @Pattern(regexp = "\\d{10}", message = "Código SIGTAP deve conter exatamente 10 dígitos.")
            String codigo
    ) {
        return service.cbosDoProcedimento(codigo);
    }

    @GetMapping(value = "/cid/{codigoCid}/procedimentos", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Procedimentos cobertos para o diagnóstico CID-10 informado")
    public List<SigtapProcedimentoResponse> procedimentosDoCid(
            @Parameter(description = "Código CID-10 (3 a 7 caracteres alfanuméricos)", example = "I10", required = true)
            @PathVariable
            @Pattern(regexp = "[A-Za-z][0-9A-Za-z\\.]{1,6}", message = "Código CID-10 inválido.")
            String codigoCid
    ) {
        return service.procedimentosDoCid(codigoCid.toUpperCase());
    }

    @GetMapping(value = "/procedimentos/{codigo}/cids", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "CIDs autorizados para o procedimento — evita glosa por incompatibilidade diagnóstica")
    public List<SigtapCidResponse> cidsDoProcedimento(
            @PathVariable
            @Pattern(regexp = "\\d{10}", message = "Código SIGTAP deve conter exatamente 10 dígitos.")
            String codigo
    ) {
        return service.cidsDoProcedimento(codigo);
    }

    @GetMapping(value = "/procedimentos/valores", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Ranking financeiro: procedimentos ordenados por valor total (SA+SH+SP)",
            description = "Use ordenar=maior_valor (default) ou ordenar=menor_valor. Filtre por grupo de 2 dígitos para focar em uma área (ex: 03 = clínicos).")
    public List<SigtapValorResumoResponse> rankingValores(
            @RequestParam(value = "grupo", required = false) String grupo,
            @RequestParam(value = "ordenar", defaultValue = "maior_valor") String ordenar,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return service.rankingValores(grupo, ordenar, Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping(value = "/exportacao/mes-atual", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Dump JSON denormalizado da competência ativa",
            description = "Documento completo com procedimentos + mapas auxiliares CBO/CID + relacionamentos. Substitui o pacote posicional legado do DataSUS por payload já mastigado.")
    public SigtapExportResponse exportar() {
        return service.exportarMesAtual();
    }

    @GetMapping(value = "/auditoria", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Auditoria de compatibilidade CBO × procedimento (legacy, agora calibrada pelo SIGTAP real)")
    public AuditoriaSigtapDTO auditoria(
            @RequestParam("ibge") String ibge,
            @RequestParam("cbo") String cbo,
            @RequestParam("procedimento") String procedimento) {
        return service.auditarProcedimento(ibge, cbo, procedimento);
    }
}
