package br.com.cernebr.gateway_nacional.cadastral.cnpj.service;

import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnaeDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.CnpjConsolidadoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.ContatosDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.EnderecoCompletoDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.InformacoesSimplesMeiDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.MunicipioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.NaturezaJuridicaDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SituacaoCadastralDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SocioDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TelefoneDTO;
import br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.TipoEstabelecimento;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Mesclador campo-a-campo dos parciais entregues pelos providers.
 *
 * <p><b>Estratégia:</b> a lista {@code parciais} chega <em>já ordenada</em>
 * por prioridade decrescente do orquestrador
 * (CnpjWs &gt; OpenCnpj &gt; CnpjA &gt; ReceitaWS &gt; ArchiveCnpjPw). Para
 * cada campo, o merger pega o primeiro valor não-nulo varrendo nessa ordem;
 * isso garante que os providers primários têm preferência mas nenhum dado é
 * perdido quando um campo só foi devolvido por um secundário.</p>
 *
 * <p><b>QSA:</b> mantém a lista do primeiro provider que devolveu QSA
 * não-vazio. Mesclar por documento mascarado é inviável (a máscara LGPD
 * destrói o discriminador) — preferimos um QSA canônico do provider mais
 * confiável a uma união ruidosa.</p>
 *
 * <p><b>Empresário Individual (NJ 213-5):</b> mesmo que algum provider tenha
 * vazado um QSA (parser bugado), o output canônico devolve QSA vazia.</p>
 */
@UtilityClass
class CnpjConsolidadoMerger {

    static CnpjConsolidadoDTO merge(String ni,
                                    List<CnpjConsolidadoDTO> parciais,
                                    List<String> fontesSobreviventes) {
        if (parciais.isEmpty()) {
            throw new IllegalStateException("Merger não pode ser invocado sem parciais sobreviventes.");
        }

        String niCanonico = firstNonBlank(parciais, CnpjConsolidadoDTO::ni, ni);
        TipoEstabelecimento tipo = firstNonNull(parciais, p ->
                p.tipoEstabelecimento() != null && p.tipoEstabelecimento() != TipoEstabelecimento.DESCONHECIDO
                        ? p.tipoEstabelecimento() : null);
        if (tipo == null) tipo = TipoEstabelecimento.DESCONHECIDO;

        String nomeEmpresarial = firstNonBlank(parciais, CnpjConsolidadoDTO::nomeEmpresarial, null);
        String nomeFantasia = firstNonBlank(parciais, CnpjConsolidadoDTO::nomeFantasia, null);

        SituacaoCadastralDTO situacao = mergeSituacao(parciais);
        NaturezaJuridicaDTO nj = mergeNaturezaJuridica(parciais);
        LocalDate dataAbertura = firstNonNull(parciais, CnpjConsolidadoDTO::dataAbertura);
        CnaeDTO cnaePrincipal = mergeCnaePrincipal(parciais);
        List<CnaeDTO> cnaeSecundarias = firstNonEmpty(parciais, CnpjConsolidadoDTO::cnaeSecundarias, List.of());
        EnderecoCompletoDTO endereco = mergeEndereco(parciais);
        ContatosDTO contatos = mergeContatos(parciais);
        BigDecimal capital = firstNonNull(parciais, CnpjConsolidadoDTO::capitalSocial);
        String porte = firstNonBlank(parciais, CnpjConsolidadoDTO::porte, null);
        InformacoesSimplesMeiDTO simples = mergeSimples(parciais);
        List<SocioDTO> qsa = mergeQsa(parciais, nj);

        return new CnpjConsolidadoDTO(
                niCanonico,
                tipo,
                nomeEmpresarial,
                nomeFantasia,
                situacao,
                nj,
                dataAbertura,
                cnaePrincipal,
                cnaeSecundarias,
                endereco,
                contatos,
                capital,
                porte,
                simples,
                qsa,
                fontesSobreviventes);
    }

    private static String firstNonBlank(List<CnpjConsolidadoDTO> ps,
                                        Function<CnpjConsolidadoDTO, String> getter,
                                        String fallback) {
        for (CnpjConsolidadoDTO p : ps) {
            String v = getter.apply(p);
            if (v != null && !v.isBlank()) return v;
        }
        return fallback;
    }

    private static <T> T firstNonNull(List<CnpjConsolidadoDTO> ps,
                                      Function<CnpjConsolidadoDTO, T> getter) {
        for (CnpjConsolidadoDTO p : ps) {
            T v = getter.apply(p);
            if (v != null) return v;
        }
        return null;
    }

    private static <T> List<T> firstNonEmpty(List<CnpjConsolidadoDTO> ps,
                                             Function<CnpjConsolidadoDTO, List<T>> getter,
                                             List<T> fallback) {
        for (CnpjConsolidadoDTO p : ps) {
            List<T> v = getter.apply(p);
            if (v != null && !v.isEmpty()) return v;
        }
        return fallback;
    }

    private static SituacaoCadastralDTO mergeSituacao(List<CnpjConsolidadoDTO> ps) {
        String codigo = null, descricao = null, motivo = null;
        LocalDate data = null;
        for (CnpjConsolidadoDTO p : ps) {
            SituacaoCadastralDTO s = p.situacaoCadastral();
            if (s == null) continue;
            if (codigo == null && s.codigo() != null) codigo = s.codigo();
            if (descricao == null && s.descricao() != null) descricao = s.descricao();
            if (data == null && s.data() != null) data = s.data();
            if (motivo == null && s.motivo() != null) motivo = s.motivo();
        }
        if (codigo == null && descricao == null && data == null && motivo == null) return null;
        return new SituacaoCadastralDTO(codigo, descricao, data, motivo);
    }

    private static NaturezaJuridicaDTO mergeNaturezaJuridica(List<CnpjConsolidadoDTO> ps) {
        String codigo = null, descricao = null;
        for (CnpjConsolidadoDTO p : ps) {
            NaturezaJuridicaDTO n = p.naturezaJuridica();
            if (n == null) continue;
            if (codigo == null && n.codigo() != null) codigo = n.codigo();
            if (descricao == null && n.descricao() != null) descricao = n.descricao();
        }
        if (codigo == null && descricao == null) return null;
        return new NaturezaJuridicaDTO(codigo, descricao);
    }

    private static CnaeDTO mergeCnaePrincipal(List<CnpjConsolidadoDTO> ps) {
        String codigo = null, descricao = null;
        for (CnpjConsolidadoDTO p : ps) {
            CnaeDTO c = p.cnaePrincipal();
            if (c == null) continue;
            if (codigo == null && c.codigo() != null) codigo = c.codigo();
            if (descricao == null && c.descricao() != null) descricao = c.descricao();
        }
        if (codigo == null && descricao == null) return null;
        return new CnaeDTO(codigo, descricao);
    }

    private static EnderecoCompletoDTO mergeEndereco(List<CnpjConsolidadoDTO> ps) {
        String logradouro = null, numero = null, complemento = null, bairro = null, cep = null, uf = null;
        MunicipioDTO municipio = null;
        for (CnpjConsolidadoDTO p : ps) {
            EnderecoCompletoDTO e = p.enderecoCompleto();
            if (e == null) continue;
            if (logradouro == null) logradouro = e.logradouro();
            if (numero == null) numero = e.numero();
            if (complemento == null) complemento = e.complemento();
            if (bairro == null) bairro = e.bairro();
            if (cep == null) cep = e.cep();
            if (uf == null) uf = e.uf();
            if (municipio == null) municipio = e.municipio();
            else if (municipio.codigoIbge() == null && e.municipio() != null && e.municipio().codigoIbge() != null) {
                municipio = new MunicipioDTO(e.municipio().codigoIbge(), municipio.nome());
            }
        }
        if (logradouro == null && cep == null && uf == null) return null;
        return new EnderecoCompletoDTO(logradouro, numero, complemento, bairro, cep, municipio, uf);
    }

    private static ContatosDTO mergeContatos(List<CnpjConsolidadoDTO> ps) {
        List<TelefoneDTO> telefones = null;
        String email = null;
        for (CnpjConsolidadoDTO p : ps) {
            ContatosDTO c = p.contatos();
            if (c == null) continue;
            if (telefones == null && c.telefones() != null && !c.telefones().isEmpty()) {
                telefones = c.telefones();
            }
            if (email == null && c.correioEletronico() != null && !c.correioEletronico().isBlank()) {
                email = c.correioEletronico();
            }
        }
        if (telefones == null && email == null) return ContatosDTO.empty();
        return new ContatosDTO(telefones != null ? telefones : List.of(), email);
    }

    private static InformacoesSimplesMeiDTO mergeSimples(List<CnpjConsolidadoDTO> ps) {
        Boolean optSimples = null, optMei = null;
        List<br.com.cernebr.gateway_nacional.cadastral.cnpj.dto.SimplesPeriodoDTO> periodos = null;
        for (CnpjConsolidadoDTO p : ps) {
            InformacoesSimplesMeiDTO s = p.informacoesSimplesMei();
            if (s == null) continue;
            if (optSimples == null && s.optanteSimples() != null) optSimples = s.optanteSimples();
            if (optMei == null && s.optanteMei() != null) optMei = s.optanteMei();
            if ((periodos == null || periodos.isEmpty()) && s.listaPeriodos() != null && !s.listaPeriodos().isEmpty()) {
                periodos = s.listaPeriodos();
            }
        }
        if (optSimples == null && optMei == null && (periodos == null || periodos.isEmpty())) {
            return null;
        }
        return new InformacoesSimplesMeiDTO(optSimples, optMei,
                periodos != null ? periodos : List.of());
    }

    private static List<SocioDTO> mergeQsa(List<CnpjConsolidadoDTO> ps, NaturezaJuridicaDTO nj) {
        if (nj != null && nj.isEmpresarioIndividual()) {
            return List.of();
        }
        for (CnpjConsolidadoDTO p : ps) {
            if (p.qsa() != null && !p.qsa().isEmpty()) {
                return new ArrayList<>(p.qsa());
            }
        }
        return List.of();
    }
}
