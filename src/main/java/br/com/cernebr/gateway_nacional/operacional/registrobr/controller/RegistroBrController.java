package br.com.cernebr.gateway_nacional.operacional.registrobr.controller;

import br.com.cernebr.gateway_nacional.operacional.registrobr.dto.RegistroBrResponse;
import br.com.cernebr.gateway_nacional.operacional.registrobr.service.RegistroBrService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/operacional/registrobr")
@Tag(
        name = "Registro.br — Disponibilidade de Domínio",
        description = "Consulta WHOIS de disponibilidade de domínios .br no NIC.br. Hedge paralelo entre Registro.br oficial e BrasilAPI."
)
public class RegistroBrController {

    /**
     * Aceita qualquer rótulo .br: letras (incluindo acentos para IDN), dígitos,
     * hífen e ponto. O sanitize completo é responsabilidade do NIC.br — aqui
     * só barramos sequências obviamente inválidas (espaços, caracteres de
     * controle) antes do round-trip.
     */
    private static final String DOMAIN_REGEX = "^[A-Za-z0-9àáâãéêíóôõúüçÀÁÂÃÉÊÍÓÔÕÚÜÇ.-]{2,253}$";

    private final RegistroBrService registroBrService;

    public RegistroBrController(RegistroBrService registroBrService) {
        this.registroBrService = registroBrService;
    }

    @GetMapping(value = "/{dominio}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar a disponibilidade de um domínio .br",
            description = """
                    Devolve o estado atual do domínio no registro do NIC.br — \
                    AVAILABLE, UNAVAILABLE, EXPIRED, WAITING etc. Útil para \
                    monitoramento de drop-catch, due diligence de marcas e \
                    pipelines de onboarding que validam se um cliente \
                    realmente controla um domínio antes de ativar serviços.

                    **Engine de Resiliência:**
                    - **Hedge paralelo:** Registro.br direto (ajax/avail) + \
                      BrasilAPI. Vence o primeiro a responder.
                    - **Cache:** {@code registroBr} hard-TTL 10min — curto \
                      o bastante para refletir movimentações reais do registro \
                      e absorver dashboards de monitoramento."""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Domínio resolvido",
                    content = @Content(schema = @Schema(implementation = RegistroBrResponse.class))),
            @ApiResponse(responseCode = "400", description = "Domínio em formato inválido",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Registro.br e BrasilAPI indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public RegistroBrResponse consultar(
            @Parameter(description = "Domínio .br canônico (com TLD)", example = "google.com.br", required = true)
            @PathVariable
            @Pattern(regexp = DOMAIN_REGEX, message = "Informe um domínio .br válido.")
            String dominio
    ) {
        return registroBrService.consultar(dominio);
    }
}
