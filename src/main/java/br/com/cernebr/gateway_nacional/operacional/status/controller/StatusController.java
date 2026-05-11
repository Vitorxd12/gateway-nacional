package br.com.cernebr.gateway_nacional.operacional.status.controller;

import br.com.cernebr.gateway_nacional.operacional.status.dto.StatusResponse;
import br.com.cernebr.gateway_nacional.operacional.status.service.StatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
@Tag(
        name = "Operacional — Status do Gateway",
        description = "Snapshot consolidado, público e sem auth da saúde do gateway e dos providers upstream — formato amigável para status pages externos (estilo status.cernebr.com) e uptime monitors."
)
public class StatusController {

    private final StatusService statusService;

    public StatusController(StatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Snapshot público da saúde do gateway e providers upstream",
            description = """
                    Endpoint dedicado para alimentar status pages, dashboards \
                    externos e uptime monitors sem necessidade de parsear \
                    {@code /actuator/health} (que mistura JVM/Redis/providers \
                    num único blob e em produção fica protegido por \
                    {@code show-details: when-authorized}).

                    **Três níveis de granularidade:**

                    1. **Gateway (processo)** — uptime, ping no Redis, status \
                       agregado;
                    2. **Por domínio funcional** ({@code cep}, {@code cambio}, \
                       {@code rastreio}, ...) — status calculado a partir das \
                       métricas {@code gateway.provider.requests};
                    3. **Por provider individual** dentro de cada domínio — \
                       taxa de sucesso, latência média e estado do \
                       Circuit Breaker Resilience4j associado.

                    **Critério de status por provider:**
                    - CB {@code OPEN}/{@code FORCED_OPEN} OU taxa de falha &gt; 50% → {@code indisponivel}
                    - CB {@code HALF_OPEN} OU taxa de falha entre 5% e 50% → {@code degradado}
                    - CB {@code CLOSED} (ou ausente) E taxa de falha ≤ 5% → {@code operacional}
                    - Sem requisições registradas no histórico → {@code sem_trafego}

                    **Critério do domínio:** pior caso entre os providers \
                    ativos. Se todos os providers de um domínio estão \
                    {@code sem_trafego} (ainda não foram exercidos nesta JVM), \
                    o domínio fica {@code sem_trafego} também — evita falsa \
                    sensação de saúde antes da primeira request.

                    **Sem auth, sem cache de servidor.** A resposta vem com \
                    {@code Cache-Control: no-store} para impedir que CDN ou \
                    browser congele um snapshot e mascare incidentes em curso. \
                    O custo de gerar o snapshot é ~50 ms (iteração linear sobre \
                    meters + ping Redis); status pages bem comportados batem \
                    1×/min e cabem nesse orçamento confortavelmente."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Snapshot consolidado emitido",
                    content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    })
    public ResponseEntity<StatusResponse> snapshot() {
        // Cache-Control no-store: força status pages a sempre virem ao gateway.
        // Aceita o trade-off de carga (1×/min × N monitors) em troca de
        // garantir que um incidente entre janelas de cache não seja mascarado.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(statusService.snapshot());
    }
}
