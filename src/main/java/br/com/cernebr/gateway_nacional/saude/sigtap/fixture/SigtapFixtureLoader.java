package br.com.cernebr.gateway_nacional.saude.sigtap.fixture;

import br.com.cernebr.gateway_nacional.saude.sigtap.etl.SigtapEtlException;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Cid;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.Procedimento;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCbo;
import br.com.cernebr.gateway_nacional.saude.sigtap.model.SigtapModels.ProcedimentoCid;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * Carregador do fixture JSON embarcado em
 * {@code classpath:data/sigtap-fixture/seed.json}.
 *
 * <p>Tem um único propósito: popular o SQLite na primeira execução do
 * gateway com o cron habilitado, antes que o DataSUS responda ao
 * primeiro download. Habilitado por {@code gateway.saude.sigtap.etl.seed-on-empty}.
 * Contém ~5 procedimentos reais (códigos 0201010042, 0301010072 etc.)
 * com seus relacionamentos CBO/CID — suficiente para demo, smoke tests
 * e uso operacional limitado antes do primeiro download mensal.</p>
 */
@Component
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapFixtureLoader {

    private static final String FIXTURE_PATH = "data/sigtap-fixture/seed.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Fixture load() {
        try (InputStream in = new ClassPathResource(FIXTURE_PATH).getInputStream()) {
            return objectMapper.readValue(in, Fixture.class);
        } catch (Exception ex) {
            throw new SigtapEtlException("Falha ao carregar fixture SIGTAP embarcado: " + ex.getMessage(), ex);
        }
    }

    public List<Procedimento> toProcedimentos(long datasetId, Fixture f) {
        return f.procedimentos().stream().map(p -> new Procedimento(
                datasetId,
                p.codigo(),
                p.nome(),
                p.complexidade(),
                p.sexo(),
                p.idadeMinimaDias(),
                p.idadeMaximaDias(),
                p.quantidadeMaxima(),
                p.tipoFinanciamento(),
                p.valorSa(), p.valorSh(), p.valorSp(),
                p.grupoCodigo(), p.subgrupoCodigo(), p.formaOrganizacaoCodigo(),
                f.competencia()
        )).toList();
    }

    public List<Cbo> toCbos(long datasetId, Fixture f) {
        return f.cbos().stream().map(c -> new Cbo(datasetId, c.codigo(), c.nome())).toList();
    }

    public List<Cid> toCids(long datasetId, Fixture f) {
        return f.cids().stream().map(c -> new Cid(datasetId, c.codigo(), c.nome())).toList();
    }

    public List<ProcedimentoCbo> toProcCbo(long datasetId, Fixture f) {
        return f.procedimentoCbo().stream()
                .map(r -> new ProcedimentoCbo(datasetId, r.procedimentoCodigo(), r.cboCodigo()))
                .toList();
    }

    public List<ProcedimentoCid> toProcCid(long datasetId, Fixture f) {
        return f.procedimentoCid().stream()
                .map(r -> new ProcedimentoCid(datasetId, r.procedimentoCodigo(), r.cidCodigo(),
                        Boolean.TRUE.equals(r.obrigatorio())))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fixture(
            String competencia,
            String revisao,
            String fonte,
            List<GrupoJson> grupos,
            List<SubgrupoJson> subgrupos,
            List<FormaOrgJson> formasOrganizacao,
            List<ProcedimentoJson> procedimentos,
            List<CodigoNomeJson> cbos,
            List<CodigoNomeJson> cids,
            List<RelProcCboJson> procedimentoCbo,
            List<RelProcCidJson> procedimentoCid
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GrupoJson(String codigo, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubgrupoJson(String codigo, String grupoCodigo, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FormaOrgJson(String codigo, String subgrupoCodigo, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcedimentoJson(
            String codigo,
            String nome,
            String complexidade,
            String sexo,
            Integer idadeMinimaDias,
            Integer idadeMaximaDias,
            Integer quantidadeMaxima,
            String tipoFinanciamento,
            BigDecimal valorSa,
            BigDecimal valorSh,
            BigDecimal valorSp,
            String grupoCodigo,
            String subgrupoCodigo,
            String formaOrganizacaoCodigo
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodigoNomeJson(String codigo, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelProcCboJson(String procedimentoCodigo, String cboCodigo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelProcCidJson(String procedimentoCodigo, String cidCodigo, Boolean obrigatorio) {
    }
}
