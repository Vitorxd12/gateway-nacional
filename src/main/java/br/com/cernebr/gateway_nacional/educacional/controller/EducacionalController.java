package br.com.cernebr.gateway_nacional.educacional.controller;

import br.com.cernebr.gateway_nacional.educacional.dto.EducacionalDashboardResponse;
import br.com.cernebr.gateway_nacional.educacional.service.EducacionalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/educacional")
@RequiredArgsConstructor
@Validated
@Tag(name = "Educacional", description = "Módulo de Dados Educacionais (PNAD e Censo Escolar)")
public class EducacionalController {

    private final EducacionalService educacionalService;

    @Operation(summary = "Obter o Dashboard Educacional", description = "Retorna os dados da PNAD e Censo Escolar cruzados para uma UF.")
    @GetMapping("/dashboard")
    public ResponseEntity<EducacionalDashboardResponse> getDashboard(
            @RequestParam @NotBlank @Size(min = 2, max = 2) String uf,
            @RequestParam @NotBlank String ibgeUfId) {
        
        EducacionalDashboardResponse response = educacionalService.getDashboardData(uf.toUpperCase(), ibgeUfId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Obter dados de Fluxo Escolar", description = "Retorna aprovação, reprovação, evasão e distorção idade-série.")
    @GetMapping("/fluxo")
    public ResponseEntity<EducacionalDashboardResponse.FluxoEscolar> getFluxoEscolar(
            @RequestParam @NotBlank @Size(min = 2, max = 2) String uf) {
        return ResponseEntity.ok(educacionalService.getFluxoEscolar(uf.toUpperCase()));
    }

    @Operation(summary = "Obter dados de Infraestrutura", description = "Retorna percentual de acesso à internet, laboratórios e acessibilidade.")
    @GetMapping("/infraestrutura")
    public ResponseEntity<EducacionalDashboardResponse.InfraestruturaEscolar> getInfraestrutura(
            @RequestParam @NotBlank @Size(min = 2, max = 2) String uf) {
        return ResponseEntity.ok(educacionalService.getInfraestrutura(uf.toUpperCase()));
    }

    @Operation(summary = "Obter dados do ENEM e Ensino Superior", description = "Retorna nota média do ENEM e dados de matrículas no ensino superior.")
    @GetMapping("/enem-superior")
    public ResponseEntity<EducacionalDashboardResponse.EnemSuperior> getEnemSuperior(
            @RequestParam @NotBlank @Size(min = 2, max = 2) String uf) {
        return ResponseEntity.ok(educacionalService.getEnemSuperior(uf.toUpperCase()));
    }

    @Operation(summary = "Obter repasses do FNDE", description = "Retorna valores repassados do PNAE, PNATE e PDDE.")
    @GetMapping("/fnde")
    public ResponseEntity<EducacionalDashboardResponse.FndeRepasses> getFnde(
            @RequestParam @NotBlank @Size(min = 2, max = 2) String uf) {
        return ResponseEntity.ok(educacionalService.getFndeRepasses(uf.toUpperCase()));
    }

    @Operation(summary = "Obter contexto socioeconômico", description = "Retorna taxa de jovens nem-nem e nível de instrução.")
    @GetMapping("/contexto")
    public ResponseEntity<EducacionalDashboardResponse.ContextoSocioeconomico> getContexto(
            @RequestParam @NotBlank String ibgeUfId) {
        return ResponseEntity.ok(educacionalService.getContextoSocioeconomico(ibgeUfId));
    }
}
