package br.com.cernebr.gateway_nacional.financeiro.boletos.controller;

import br.com.cernebr.gateway_nacional.financeiro.boletos.dto.BoletoResponse;
import br.com.cernebr.gateway_nacional.financeiro.boletos.service.BoletoParserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/financeiro/boletos")
@Tag(
        name = "Financeiro — Boletos",
        description = "Validação e extração de dados de linhas digitáveis FEBRABAN — boletos bancários e guias de arrecadação. 100% algorítmico, sem rede."
)
public class BoletoController {

    /**
     * Aceita números, espaços, pontos e hífens — o serviço normaliza para
     * apenas dígitos antes de processar. {@code @Size} amarra a janela de
     * comprimento *bruto* (44 a 80 chars) para impedir inputs absurdos
     * antes mesmo de chegar no parser.
     */
    private static final String LINHA_REGEX = "^[\\d\\s.\\-]+$";

    private final BoletoParserService boletoParserService;

    public BoletoController(BoletoParserService boletoParserService) {
        this.boletoParserService = boletoParserService;
    }

    @GetMapping(value = "/parse", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Validar e extrair dados de uma linha digitável FEBRABAN",
            description = """
                    Identifica o tipo do boleto pelo comprimento da entrada \
                    (47 dígitos = bancário, 48 = arrecadação, 44 = código de \
                    barras direto), valida os DACs (módulos 10/11 conforme \
                    o layout) e extrai banco, valor e vencimento.

                    **Latência**: O(comprimento) puramente em CPU. Sem \
                    chamadas externas, sem cache, sem Redis. P50 < 1ms.

                    **Vencimento**: calculado pelo Fator de Vencimento \
                    FEBRABAN (4 dígitos) com tratamento da virada de \
                    22/02/2025. Em arrecadação fica `null` (o layout do \
                    campo livre é específico de cada órgão emissor).

                    **Erros**: linha com comprimento inválido ou DAC \
                    incorreto retorna `400 Bad Request` com a posição do \
                    dígito que falhou."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Boleto válido — dados extraídos",
                    content = @Content(schema = @Schema(implementation = BoletoResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Linha digitável inválida (formato, comprimento, ou DAC)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    public BoletoResponse parse(
            @Parameter(
                    description = "Linha digitável de 47 (bancário) ou 48 dígitos (arrecadação), ou código de barras de 44 dígitos. Pontos, hífens e espaços são tolerados.",
                    example = "23793.38128 60082.231609 95000.063305 9 89220000026035",
                    required = true
            )
            @RequestParam("linha")
            @NotBlank(message = "A linha digitável não pode ser vazia.")
            @Size(min = 44, max = 80, message = "A linha deve ter entre 44 e 80 caracteres (incluindo separadores opcionais).")
            String linha
    ) {
        return boletoParserService.parse(linha);
    }
}
