package br.com.cernebr.gateway_nacional.financeiro.pix.controller;

import br.com.cernebr.gateway_nacional.financeiro.pix.dto.PixParticipantesResponse;
import br.com.cernebr.gateway_nacional.financeiro.pix.service.PixService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/financeiro/pix")
@Tag(
        name = "PIX",
        description = "Listagem de instituições participantes do arranjo PIX. Cascata BrasilAPI → CSV oficial do BCB com retry retroativo de até 7 dias para cobrir feriados e fins de semana."
)
public class PixController {

    private final PixService pixService;

    public PixController(PixService pixService) {
        this.pixService = pixService;
    }

    @GetMapping(value = "/participants", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Listar participantes do PIX",
            description = "Retorna todas as instituições autorizadas pelo BACEN no arranjo PIX. Resposta envelopada com total, fonte que respondeu e data efetiva do snapshot. Cacheado por 24h em Redis com refresh-ahead a partir de 6h."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de participantes",
                    content = @Content(schema = @Schema(implementation = PixParticipantesResponse.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "BrasilAPI e BCB CSV (todos os dias retroativos) indisponíveis",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public PixParticipantesResponse listParticipantes() {
        return pixService.listParticipantes();
    }
}
