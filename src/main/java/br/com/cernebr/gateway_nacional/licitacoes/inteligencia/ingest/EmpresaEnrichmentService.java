package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnaeDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.EnderecoCompletoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.MunicipioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.service.CnpjConsolidadoService;
import br.com.cernebr.gateway_nacional.cadastral.cep.service.IbgeEnrichmentService;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.Empresa;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.model.IntelModels.EmpresaCnae;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository.EmpresaCnaeRepository;
import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.repository.EmpresaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriquecimento de uma empresa (fornecedor) via o módulo {@code cadastral/cnpj}
 * — CNAE principal/secundárias, porte, município + código IBGE do endereço RFB
 * (com backfill por nome quando o provider não traz o IBGE).
 *
 * <p><b>M2.5 — desacoplado da ingestão:</b> a ingestão do PNCP grava só o stub
 * ({@code cnpj}+{@code razao}); este serviço faz o lookup pesado (que sofre
 * throttle dos providers) e é chamado pelo {@code EmpresaBackfillWorker} em
 * lotes pequenos com delay, nunca em rajada inline.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class EmpresaEnrichmentService {

    private final CnpjConsolidadoService cnpjService;
    private final EmpresaRepository empresaRepo;
    private final EmpresaCnaeRepository cnaeRepo;
    private final TransactionTemplate tx;
    private final IbgeEnrichmentService ibge;

    public EmpresaEnrichmentService(CnpjConsolidadoService cnpjService,
                                    EmpresaRepository empresaRepo,
                                    EmpresaCnaeRepository cnaeRepo,
                                    @Qualifier("licIntelTxTemplate") TransactionTemplate tx,
                                    IbgeEnrichmentService ibge) {
        this.cnpjService = cnpjService;
        this.empresaRepo = empresaRepo;
        this.cnaeRepo = cnaeRepo;
        this.tx = tx;
        this.ibge = ibge;
    }

    /**
     * Busca o CNPJ consolidado e atualiza CNAE/IBGE/porte da empresa (que já
     * existe como stub). Devolve {@code true} em sucesso. Em falha (throttle/erro
     * do provider), apenas marca a tentativa para o worker rotacionar a fila —
     * a empresa segue pendente e será re-tentada depois.
     */
    public boolean enriquecer(String cnpj) {
        try {
            CnpjConsolidadoDTO dto = cnpjService.findByCnpj(cnpj);
            String fallbackRazao = empresaRepo.findByCnpj(cnpj).map(Empresa::razaoSocial).orElse(null);
            Empresa empresa = toEmpresa(dto, cnpj, fallbackRazao);
            List<EmpresaCnae> cnaes = toCnaes(dto, cnpj);
            tx.executeWithoutResult(s -> {
                empresaRepo.upsert(empresa);
                cnaeRepo.replaceForEmpresa(cnpj, cnaes);
            });
            return true;
        } catch (RuntimeException ex) {
            log.warn("[LIC-INTEL] Enriquecimento CNPJ {} falhou: {}", cnpj, ex.toString());
            empresaRepo.marcarTentativaFalha(cnpj);
            return false;
        }
    }

    private Empresa toEmpresa(CnpjConsolidadoDTO dto, String cnpj, String fallbackRazao) {
        EnderecoCompletoDTO end = dto.enderecoCompleto();
        MunicipioDTO mun = end != null ? end.municipio() : null;
        String cnaePrincipal = dto.cnaePrincipal() != null ? dto.cnaePrincipal().codigo() : null;
        String razao = dto.nomeEmpresarial() != null ? dto.nomeEmpresarial()
                : (fallbackRazao != null ? fallbackRazao : "CNPJ " + cnpj);
        String uf = end != null ? end.uf() : null;
        String municipioNome = mun != null ? mun.nome() : null;
        // Muitos providers de CNPJ devolvem o município SEM código IBGE; fazemos
        // backfill (UF + nome) pelo mesmo registro do IbgeEnrichmentService.
        String municipioIbge = mun != null && mun.codigoIbge() != null
                ? mun.codigoIbge()
                : ibge.resolveIbge(uf, municipioNome);
        return new Empresa(
                cnpj,
                razao,
                dto.nomeFantasia(),
                cnaePrincipal,
                dto.porte(),
                null, // naturezaJuridica: DTO aninhado — fora do escopo do MVP
                uf,
                municipioNome,
                municipioIbge,
                null, // situacao: idem — preencher em iteração futura
                OffsetDateTime.now(ZoneOffset.UTC),
                null  // atualizado_em gerido pelo banco (DEFAULT now())
        );
    }

    private List<EmpresaCnae> toCnaes(CnpjConsolidadoDTO dto, String cnpj) {
        // LinkedHashMap dedup por código — principal tem prioridade sobre secundário.
        Map<String, EmpresaCnae> porCodigo = new LinkedHashMap<>();
        if (dto.cnaePrincipal() != null && dto.cnaePrincipal().codigo() != null) {
            String cod = dto.cnaePrincipal().codigo();
            porCodigo.put(cod, new EmpresaCnae(cnpj, cod, true));
        }
        if (dto.cnaeSecundarias() != null) {
            for (CnaeDTO c : dto.cnaeSecundarias()) {
                if (c != null && c.codigo() != null) {
                    porCodigo.putIfAbsent(c.codigo(), new EmpresaCnae(cnpj, c.codigo(), false));
                }
            }
        }
        return new ArrayList<>(porCodigo.values());
    }
}
