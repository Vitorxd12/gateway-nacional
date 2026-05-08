package br.com.cernebr.gateway_nacional.fiscal.cest.service;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.fiscal.cest.dto.CestResponse;
import br.com.cernebr.gateway_nacional.fiscal.cest.repository.CestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin façade over {@link CestRepository}.
 *
 * <p>Stays as a separate Spring bean even though it's a near-trivial
 * delegation, so future evolutions (e.g., filtering CESTs by segmento,
 * applying validity windows from new convênios) have a place to live
 * without breaking the controller contract. No {@code @Cacheable}
 * here — the repository is in-memory (sub-microsecond lookup); pushing
 * results through Redis would only add a network round-trip.</p>
 *
 * <h2>Semantics for {@link #findByNcm(String)}</h2>
 * <p>Returns an <strong>empty list</strong> when the NCM has no CEST
 * mapping — that is the correct fiscal answer ("this product is not
 * subject to ICMS-ST under the current convênio") and not an error.
 * The 404 contract is reserved for {@link #findByCest(String)} where
 * an unknown code is genuinely a bad request.</p>
 */
@Slf4j
@Service
public class CestService {

    private final CestRepository repository;

    public CestService(CestRepository repository) {
        this.repository = repository;
    }

    public CestResponse findByCest(String cest) {
        return repository.findByCest(cest)
                .orElseThrow(() -> new ResourceNotFoundException("CEST",
                        "CEST " + cest + " não consta na tabela do Convênio ICMS 142/2018 carregada pelo Gateway."));
    }

    public List<CestResponse> findByNcm(String ncm) {
        return repository.findByNcm(ncm);
    }
}
