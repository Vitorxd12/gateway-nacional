package br.com.cernebr.gateway_nacional.fiscal.cfop.service;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.fiscal.cfop.dto.CfopResponse;
import br.com.cernebr.gateway_nacional.fiscal.cfop.repository.CfopRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thin façade over {@link CfopRepository}.
 *
 * <p>Stays as a separate Spring bean even though it's a single-line
 * delegation, so future evolutions (e.g., serving CFOP filtered by
 * {@code unidade-federativa} or by direção da operação) have a place to
 * live without breaking the controller contract. No {@code @Cacheable}
 * here — the repository is in-memory (sub-microsecond lookup); pushing
 * results through Redis would only add a network round-trip.</p>
 */
@Slf4j
@Service
public class CfopService {

    private final CfopRepository repository;

    public CfopService(CfopRepository repository) {
        this.repository = repository;
    }

    public CfopResponse findByCodigo(String codigo) {
        return repository.findByCodigo(codigo)
                .orElseThrow(() -> new ResourceNotFoundException("CFOP",
                        "CFOP " + codigo + " não consta na tabela do Convênio SINIEF carregada pelo Gateway."));
    }
}
