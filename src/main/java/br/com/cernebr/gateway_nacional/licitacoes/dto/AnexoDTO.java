package br.com.cernebr.gateway_nacional.licitacoes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Anexo (edital, termo de referência, planilha de quantitativos) publicado
 * pelo portal. Nunca baixamos os bytes — devolvemos a URL e o tipo MIME
 * declarado, deixando o consumidor decidir se faz download.
 */
@Schema(name = "AnexoDTO",
        description = "Anexo (PDF/DOCX/XLSX) referenciado no edital.")
public record AnexoDTO(
        @Schema(description = "Título publicado pelo portal.", example = "Edital Pregão 123-2026")
        String titulo,

        @Schema(description = "URL absoluta do arquivo no portal de origem.",
                example = "https://www.gov.br/pncp/.../edital-pregao-123-2026.pdf")
        String url,

        @Schema(description = "Tipo MIME declarado pelo portal (best-effort).", example = "application/pdf")
        String contentType,

        @Schema(description = "Tamanho em bytes, quando informado.", example = "245678")
        Long tamanhoBytes
) {
}
