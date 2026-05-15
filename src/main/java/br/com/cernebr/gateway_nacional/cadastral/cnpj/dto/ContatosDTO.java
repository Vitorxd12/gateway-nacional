package br.com.cernebr.gateway_nacional.cadastral.cnpj.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "Contatos",
        description = "Bloco consolidado de contatos do estabelecimento (telefones + e-mail).")
public record ContatosDTO(
        @Schema(description = "Lista de telefones cadastrados na RFB (DDD + número).")
        List<TelefoneDTO> telefones,

        @Schema(description = "Correio eletrônico institucional registrado na RFB.",
                example = "contato@banco.com.br")
        String correioEletronico
) {

    public static ContatosDTO empty() {
        return new ContatosDTO(List.of(), null);
    }
}
