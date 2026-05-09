package br.com.cernebr.gateway_nacional.saude.ans.service;

import br.com.cernebr.gateway_nacional.exception.ResourceNotFoundException;
import br.com.cernebr.gateway_nacional.saude.ans.dto.OperadoraAnsResponse;
import br.com.cernebr.gateway_nacional.saude.ans.repository.AnsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Façade de leitura sobre {@link AnsRepository}.
 *
 * <p>Roteamento é feito na controller (ela já sabe se chegou um CNPJ ou um
 * registro). Aqui ficam só os dois métodos de busca, cada um com sua mensagem
 * de erro contextual em pt-BR — útil para o ERP exibir diretamente ao
 * operador sem ter que mapear códigos.</p>
 *
 * <p>Sem {@code @Cacheable}: o repositório é {@link java.util.HashMap} em
 * memória. Pôr o resultado em Redis significaria adicionar uma chamada de
 * rede para servir um lookup O(1) que já está no heap.</p>
 */
@Slf4j
@Service
public class AnsService {

    private static final String RESOURCE_TYPE = "ANS";

    private final AnsRepository repository;

    public AnsService(AnsRepository repository) {
        this.repository = repository;
    }

    public OperadoraAnsResponse findByRegistroAns(String registroAns) {
        return repository.findByRegistroAns(registroAns)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE,
                        "Registro ANS " + registroAns + " não consta no relatório PDA da ANS carregado pelo Gateway."));
    }

    public OperadoraAnsResponse findByCnpj(String cnpj) {
        return repository.findByCnpj(cnpj)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE,
                        "CNPJ " + cnpj + " não consta como operadora registrada na ANS."));
    }
}
