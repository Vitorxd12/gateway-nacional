# Inteligência de Cruzamento Licitações ↔ Empresas Participantes

> Domínio novo: `licitacoes.inteligencia`. Mapeamento bidirecional empresa↔licitação,
> filtros de mercado (CNAE + localização) e consulta de prospecção B2B para CRM.

## 0. Achado crítico (leia antes de tudo)

O módulo `licitacoes` **hoje é um gateway de federação puro, sem persistência**. Ele faz
fan-out ao vivo para 4 portais frágeis (ComprasNet/PNCP, BLL, BNC, LicitaNet), agrega e
cacheia no Redis. **Nenhum dado de empresa participante existe no pipeline atual** — todo
`cnpj` que aparece (`OrgaoDTO.cnpj`, `ComprasNetClient.cnpjOrgao`) é o **CNPJ do órgão
comprador**, não de fornecedor. O gateway também só enxerga **editais ativos**, não o
histórico.

Logo, esta feature **não é "adicionar uma tabela de junção"**. Ela exige:

1. **Aquisição de dado novo** — fase de **resultados/propostas** do PNCP, onde os CNPJs de
   fornecedores (`niFornecedor`, `nomeRazaoSocialFornecedor`, `valorTotalHomologado`,
   `ordemClassificacao`) realmente existem.
2. **Store analítico persistente** que o domínio nunca teve → **PostgreSQL dedicado**.

Decisões confirmadas com o time: **fonte = ingestão PNCP resultados/propostas**;
**store = PostgreSQL dedicado**.

## 1. Por que não dá para responder "ao vivo"

A pergunta "todas as licitações de uma empresa" e "empresas do setor X na cidade Y" são
consultas **invertidas pelo fornecedor**. As list-APIs dos portais são keyed por
`órgão/UF/modalidade`, nunca por CNPJ de proponente, e bloqueiam IP sob martelagem
(`cascata sequencial, não hedge`). Não existe endpoint upstream "licitações onde o CNPJ X
participou". Portanto o cruzamento **precisa ser materializado** num índice próprio,
alimentado por ETL — exatamente o padrão que o módulo **SIGTAP** já usa neste projeto
(`saude/sigtap`): datasource próprio do módulo, schema bootstrap em runtime, `@Scheduled`
cron-gated, custo zero quando desabilitado.

## 2. Arquitetura do módulo (segue o precedente SIGTAP)

```
licitacoes/
  client/
    PncpResultadosClient.java        # NOVO — /resultados do PNCP (CNPJs de fornecedor)
  inteligencia/
    config/
      IntelDataSourceConfig.java     # DataSource Postgres próprio + NamedParameterJdbcTemplate
      IntelSchemaBootstrap.java      # CREATE TABLE IF NOT EXISTS (idioma SIGTAP) ou Flyway
    domain/                          # records de domínio (Empresa, Licitacao, Participacao)
    repository/
      EmpresaRepository.java
      LicitacaoIntelRepository.java
      ParticipacaoRepository.java    # consultas de cruzamento (SQL dinâmico)
      IngestaoCursorRepository.java
    ingest/
      ParticipacaoIngestionService.java
      ParticipacaoIngestionScheduler.java   # @Scheduled, cron-gated
      EmpresaEnrichmentService.java         # reusa cadastral/cnpj
      SetorResolver.java                    # CNAE -> macro-setor (reusa cadastral/cnae)
    service/
      ParticipacaoQueryService.java         # casos de uso + cache Redis
    dto/
      FiltroProspeccao.java  EmpresaProspeccaoDTO.java  ...
    controller/
      ParticipacaoController.java
      EmpresaParticipacaoController.java
      InteligenciaController.java
```

Config gated em `application.yml` (mesmo nível de `gateway.sigtap` / `gateway.licitacoes`):

```yaml
gateway:
  licitacoes:
    inteligencia:
      enabled: ${GATEWAY_LIC_INTEL_ENABLED:false}   # boot zero-cost por padrão
      datasource:
        url: ${LIC_INTEL_DB_URL:jdbc:postgresql://localhost:5432/cerne_intel}
        username: ${LIC_INTEL_DB_USER:cerne}
        password: ${LIC_INTEL_DB_PASS:}
        hikari:
          maximum-pool-size: 16          # virtual threads são I/O-bound; pool moderado
      ingestao:
        cron: ${LIC_INTEL_CRON:0 0 3 * * *}   # diário 03:00
        janela-dias: 7                          # reprocessa últimos N dias
        rate-limit-rps: 2                       # respeita fragilidade dos portais
```

`DataSourceAutoConfiguration` continua excluída globalmente; o `@Bean` de DataSource é
`@ConditionalOnProperty(gateway.licitacoes.inteligencia.enabled=true)`, então self-hosts
que não usam o módulo não pagam nada — idêntico ao SIGTAP.

## 3. Modelagem de dados (PostgreSQL — schema `licitacoes_intel`)

```sql
-- ============ EMPRESA (mestre de fornecedor, enriquecido via cadastral/cnpj) ============
CREATE TABLE empresa (
  cnpj              CHAR(14) PRIMARY KEY,           -- normalizado, só dígitos
  razao_social      TEXT NOT NULL,
  nome_fantasia     TEXT,
  cnae_principal    CHAR(7),                        -- subclasse IBGE, ex '8599604'
  porte             TEXT,                           -- ME | EPP | DEMAIS
  natureza_juridica TEXT,
  uf                CHAR(2),
  municipio_nome    TEXT,
  municipio_ibge    CHAR(7),                        -- 7 dígitos IBGE (chave de geo exata)
  situacao          TEXT,
  enriquecido_em    TIMESTAMPTZ,                    -- último lookup CNPJ (TTL de re-enrich)
  atualizado_em     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CNAEs secundários: ramo de atuação é N:N (empresa atua em vários ramos)
CREATE TABLE empresa_cnae (
  cnpj        CHAR(14) NOT NULL REFERENCES empresa(cnpj) ON DELETE CASCADE,
  cnae        CHAR(7)  NOT NULL,
  principal   BOOLEAN  NOT NULL DEFAULT false,
  PRIMARY KEY (cnpj, cnae)
);

-- ============ LICITAÇÃO (snapshot persistido — desacopla do portal vivo) ============
CREATE TABLE licitacao (
  id                   BIGSERIAL PRIMARY KEY,
  portal               TEXT NOT NULL,              -- comprasnet | bll | bnc | licitanet
  identificador        TEXT NOT NULL,
  numero               TEXT,
  objeto               TEXT,
  modalidade           TEXT,
  setor                TEXT,                       -- macro-setor derivado (EDUCACAO, SAUDE...)
  orgao_cnpj           CHAR(14),
  orgao_nome           TEXT,
  orgao_uf             CHAR(2),
  orgao_municipio_nome TEXT,
  orgao_municipio_ibge CHAR(7),
  valor_estimado       NUMERIC(18,2),
  valor_homologado     NUMERIC(18,2),
  data_abertura        TIMESTAMPTZ,
  data_resultado       TIMESTAMPTZ,
  ano                  SMALLINT NOT NULL,
  situacao             TEXT,
  fonte                TEXT NOT NULL DEFAULT 'pncp',
  ingerido_em          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (portal, identificador)
);

-- ============ PARTICIPAÇÃO (o N:N — coração do cruzamento) ============
CREATE TABLE participacao (
  id               BIGSERIAL PRIMARY KEY,
  licitacao_id     BIGINT   NOT NULL REFERENCES licitacao(id) ON DELETE CASCADE,
  empresa_cnpj     CHAR(14) NOT NULL REFERENCES empresa(cnpj),
  papel            TEXT     NOT NULL,    -- PROPONENTE | HABILITADO | VENCEDOR | HOMOLOGADO | DESCLASSIFICADO
  item_sequencial  INT,                  -- null = participação no certame; preenchido = por item/lote
  classificacao    INT,                  -- ordem de classificação (rank)
  valor_proposta   NUMERIC(18,2),
  valor_homologado NUMERIC(18,2),
  data_resultado   TIMESTAMPTZ,
  ano              SMALLINT NOT NULL,
  fonte            TEXT NOT NULL DEFAULT 'pncp',
  ingerido_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (licitacao_id, empresa_cnpj, papel, item_sequencial)   -- idempotência do upsert
);

-- cursor de ETL (retomada incremental por portal)
CREATE TABLE ingestao_cursor (
  portal              TEXT PRIMARY KEY,
  ultima_data_proc    TIMESTAMPTZ,
  ultima_execucao     TIMESTAMPTZ
);
```

### Decisões de modelagem

- **`empresa.cnpj` como PK natural** (14 dígitos normalizados). O CNPJ é a identidade
  estável do fornecedor entre portais — evita dedupe por surrogate.
- **Duas dimensões de filtro independentes**, porque o requisito mistura as duas faces:
  - lado **edital**: `licitacao.setor`, `licitacao.orgao_municipio_ibge`, `data_resultado`
    (responde "licitações do setor de Educação em Aracaju");
  - lado **empresa**: `empresa.cnae_principal` + `empresa_cnae`, `empresa.municipio_ibge`
    (responde "filtrar empresas participantes por ramo e localização").
  O modelo permite filtrar por qualquer combinação das duas.
- **`setor`** é macro-categoria derivada do CNAE pelo `SetorResolver` (ex.: Educação → seção
  P / divisão 85; Saúde → Q/86). Guardado desnormalizado em `licitacao` para indexar barato.
  O CNAE preciso continua disponível via join com `empresa`.
- **`item_sequencial`** suporta participação por item/lote (PNCP publica resultado por item)
  sem quebrar o caso simples (certame inteiro = `null`).
- **`papel`** distingue proponente × vencedor × homologado — o CRM normalmente quer
  `VENCEDOR`/`HOMOLOGADO` para lead de quem fecha contrato, mas `PROPONENTE` serve para
  prospecção de quem disputa o mercado.
- **Geo por código IBGE** (`municipio_ibge`), não por nome. `OrgaoDTO` hoje só traz nome —
  o ETL normaliza nome+UF → código IBGE (reusar o catálogo de municípios do módulo
  `cadastral/cep`), garantindo join exato e imune a acento/caixa.

## 4. Lógica de negócio (services / use cases)

### 4.1 Ingestão (ETL — alimenta o grafo)

`PncpResultadosClient` (em `licitacoes/client`, ao lado do ComprasNetClient/PNCP):

```java
// PNCP: /v1/orgaos/{cnpj}/compras/{ano}/{seq}/resultados  (e .../itens/{n}/resultados)
public List<ResultadoFornecedor> buscarResultados(String cnpjOrgao, int ano, String seq);

public record ResultadoFornecedor(
        String niFornecedor,                 // CNPJ do fornecedor (normalizar)
        String nomeRazaoSocialFornecedor,
        String porteFornecedorNome,
        Integer ordemClassificacao,
        BigDecimal valorTotalHomologado,
        Integer itemSequencial,
        String situacao) {}
```

`ParticipacaoIngestionService` (idempotente, orquestra um certame):

```java
public void ingerir(LicitacaoDetalheDTO edital) {
    long licitacaoId = licitacaoRepo.upsert(toLicitacaoRow(edital));      // ON CONFLICT (portal,identificador)
    var resultados = pncpResultados.buscarResultados(
            edital.orgao().cnpj(), edital.ano(), edital.identificador());
    List<ParticipacaoRow> batch = new ArrayList<>();
    for (var r : resultados) {
        String cnpj = Cnpj.normalizar(r.niFornecedor());
        enrichment.garantirEmpresa(cnpj, r.nomeRazaoSocialFornecedor()); // reusa cadastral/cnpj se stale
        batch.add(toParticipacaoRow(licitacaoId, cnpj, r));
    }
    participacaoRepo.upsertBatch(batch);                                   // multi-row INSERT ... ON CONFLICT
}
```

`EmpresaEnrichmentService` — reusa `cadastral/cnpj` (`CnpjConsolidadoDTO` +
`EnderecoCompletoDTO`) para preencher CNAE principal/secundárias, porte, município+UF.
Pula re-enriquecimento se `enriquecido_em` < 30 dias (evita martelar ReceitaWS/BrasilAPI).

`ParticipacaoIngestionScheduler` — `@Scheduled(cron = ...)` cron-gated (precedente
`SigtapScheduler`): pagina a janela de publicação/atualização do PNCP
(`/v1/contratacoes/atualizacao?dataInicial=...&dataFinal=...`), retoma de `ingestao_cursor`,
respeita `rate-limit-rps` via bucket4j + resilience4j já existentes. Job de **backfill**
separado para carga histórica inicial.

### 4.2 Consulta (casos de uso — servidos a partir do grafo)

`ParticipacaoQueryService` (todas com cache Redis no padrão RefreshAheadCache do módulo,
chave = hash do filtro normalizado, soft-TTL ~30m):

```java
// bid -> empresas (filtra pela face EMPRESA: CNAE, UF, município)
Page<EmpresaParticipanteDTO> empresasDaLicitacao(
        Portal portal, String identificador, FiltroEmpresas filtro, PageRequest page);

// empresa -> bids (filtra pela face EDITAL: portal, modalidade, UF, datas, papel)
Page<LicitacaoDaEmpresaDTO> licitacoesDaEmpresa(
        String cnpj, FiltroLicitacoesEmpresa filtro, PageRequest page);

// CASO DE USO PRINCIPAL (CRM): setor+cidade do edital  ×  ramo+local da empresa
Page<EmpresaProspeccaoDTO> prospectar(FiltroProspeccao filtro, PageRequest page);
```

```java
public record FiltroProspeccao(
        // face EDITAL
        String setor,                 // "EDUCACAO" | "SAUDE" | ...
        String cnaeEdital,            // alternativa precisa ao setor macro
        String municipioOrgaoIbge,    // ex Aracaju = "2800308"  (ou nome -> resolvido p/ IBGE)
        String ufOrgao,
        OffsetDateTime dataDe,
        OffsetDateTime dataAte,
        Modalidade modalidade,
        Papel papel,                  // default VENCEDOR para lead-gen
        // face EMPRESA
        String cnaeEmpresa,
        String ufEmpresa,
        String municipioEmpresaIbge,
        String porte) {}

public record EmpresaProspeccaoDTO(
        String cnpj, String razaoSocial, String nomeFantasia,
        String cnaePrincipal, String porte, String uf, String municipio,
        int qtdParticipacoes, OffsetDateTime ultimaParticipacao,
        List<LicitacaoRefDTO> licitacoesRecentes) {}   // contexto p/ o vendedor
```

Filtros dinâmicos com `NamedParameterJdbcTemplate` (o projeto não usa JPA): a query monta
predicados condicionalmente e roda contra a **materialized view** `mv_prospeccao` (§6) no
caminho quente. Tradução literal do caso de uso do enunciado:

```sql
-- "Empresas que VENCERAM as últimas licitações do setor Educação em Aracaju"
SELECT DISTINCT ON (cnpj)
       cnpj, razao_social, nome_fantasia, cnae_principal, porte,
       empresa_uf AS uf, empresa_municipio_nome AS municipio, data_resultado
FROM   mv_prospeccao
WHERE  setor = :setor                              -- 'EDUCACAO'
  AND  edital_municipio_ibge = :municipioOrgao     -- '2800308' (Aracaju)
  AND  data_resultado >= :dataDe                   -- now() - interval '12 months'
  AND  (:papel IS NULL OR papel = :papel)          -- 'VENCEDOR'
  AND  (:cnaeEmpresa IS NULL OR cnae_principal LIKE :cnaeEmpresa || '%')
  AND  (:ufEmpresa   IS NULL OR empresa_uf = :ufEmpresa)
ORDER BY cnpj, data_resultado DESC
LIMIT :limit;
```

## 5. Endpoints (REST — alinhados ao `@RequestMapping("/v1/licitacoes")` existente)

| Método | Rota | Caso de uso |
|--------|------|-------------|
| `GET` | `/v1/licitacoes/{portal}/{identificador}/empresas` | Licitação → empresas participantes |
| `GET` | `/v1/empresas/{cnpj}/licitacoes` | Empresa → licitações em que participou |
| `GET` | `/v1/inteligencia/prospeccao` | Cruzamento CRM (filtros simples via query string) |
| `POST`| `/v1/inteligencia/prospeccao/consulta` | Cruzamento CRM (filtros complexos no body) |

```http
### Licitação -> empresas (filtra pela empresa)
GET /v1/licitacoes/comprasnet/00394460000141-2026-1230/empresas
      ?papel=VENCEDOR&cnaeEmpresa=8599&ufEmpresa=SE&page=0&size=20

### Empresa -> licitações
GET /v1/empresas/12345678000190/licitacoes
      ?portal=comprasnet&modalidade=pregao_eletronico
      &dataDe=2025-01-01T00:00:00Z&papel=PROPONENTE&page=0&size=20

### Prospecção CRM (o caso de uso principal)
GET /v1/inteligencia/prospeccao
      ?setor=EDUCACAO&municipioOrgao=2800308&dataDe=2025-06-01T00:00:00Z
      &papel=VENCEDOR&page=0&size=50

### Prospecção CRM com filtros complexos
POST /v1/inteligencia/prospeccao/consulta
Content-Type: application/json
{
  "setor": "EDUCACAO", "municipioOrgaoIbge": "2800308",
  "dataDe": "2025-06-01T00:00:00Z", "papel": "VENCEDOR",
  "cnaeEmpresa": "8599", "ufEmpresa": "SE", "porte": "EPP"
}
```

Convenções já vigentes no projeto, mantidas: records + `OffsetDateTime` em UTC,
`@JsonInclude(NON_NULL)`, OpenAPI `@Schema`/`@ApiResponses`, erros como `ProblemDetail`
(400 filtro inválido, 404 licitação não ingerida, 503 store indisponível). Paginação:
**keyset/cursor** (`?cursor=...`) no `/prospeccao` para páginas profundas; `page/size`
clássico nos endpoints de cardinalidade baixa. Para exports grandes (pull de CRM),
endpoint de cursor + `Cache-Control`/`ETag`.

## 6. Performance (base tende a ser grande)

**Índices** (cobrem as duas faces + bidirecionalidade):

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- bidirecional empresa<->licitação
CREATE INDEX ix_part_empresa   ON participacao (empresa_cnpj, data_resultado DESC);
CREATE INDEX ix_part_licitacao ON participacao (licitacao_id);

-- face edital
CREATE INDEX ix_lic_setor_mun_data ON licitacao (setor, orgao_municipio_ibge, data_resultado DESC);
CREATE INDEX ix_lic_uf_modalidade  ON licitacao (orgao_uf, modalidade);
CREATE INDEX ix_lic_objeto_trgm    ON licitacao USING gin (objeto gin_trgm_ops);  -- busca textual

-- face empresa
CREATE INDEX ix_emp_cnae    ON empresa (cnae_principal);
CREATE INDEX ix_emp_mun     ON empresa (municipio_ibge);
CREATE INDEX ix_emp_uf      ON empresa (uf);
CREATE INDEX ix_emp_razao_trgm ON empresa USING gin (razao_social gin_trgm_ops);
CREATE INDEX ix_empcnae_cnae   ON empresa_cnae (cnae, cnpj);
```

**Materialized view para o caminho quente do CRM** (achata participação⨝licitação⨝empresa):

```sql
CREATE MATERIALIZED VIEW mv_prospeccao AS
SELECT p.id AS participacao_id, p.papel, p.data_resultado,
       l.id AS licitacao_id, l.portal, l.identificador, l.numero, l.objeto, l.setor,
       l.orgao_uf AS edital_uf, l.orgao_municipio_ibge AS edital_municipio_ibge,
       l.orgao_municipio_nome AS edital_municipio_nome,
       e.cnpj, e.razao_social, e.nome_fantasia, e.cnae_principal, e.porte,
       e.uf AS empresa_uf, e.municipio_ibge AS empresa_municipio_ibge,
       e.municipio_nome AS empresa_municipio_nome
FROM participacao p
JOIN licitacao l ON l.id = p.licitacao_id
JOIN empresa   e ON e.cnpj = p.empresa_cnpj;

CREATE UNIQUE INDEX ux_mvprosp        ON mv_prospeccao (participacao_id);   -- exige REFRESH CONCURRENTLY
CREATE INDEX        ix_mvprosp_setor  ON mv_prospeccao (setor, edital_municipio_ibge, data_resultado DESC);
CREATE INDEX        ix_mvprosp_empcnae ON mv_prospeccao (cnae_principal, empresa_municipio_ibge);
-- pós-batch de ETL:
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_prospeccao;
```

**Demais alavancas**

- **Cache Redis** (RefreshAheadCache já existente): resultado de `/prospeccao` keyed pelo
  hash do filtro, soft-TTL 30m — mesmo padrão da listagem de editais ativos.
- **Particionamento por `ano`** (RANGE) em `licitacao` e `participacao` quando o volume
  crescer: filtros temporais ("últimas licitações") fazem partition pruning. Aplicar como
  evolução, mantendo o schema base acima até justificar.
- **Keyset pagination** em vez de `OFFSET` para páginas profundas.
- **Upserts em lote** no ETL (`INSERT ... ON CONFLICT` multi-row, ou `COPY` no backfill).
- **HikariCP** dimensionado para virtual threads (pool moderado, ~16; conexões são
  I/O-bound). Em escala, **read replica** + PgBouncer para as queries analíticas.
- **Geo por IBGE**: normalizar nome→código no ETL (uma vez) em vez de `LIKE` por nome em
  cada consulta — join exato e indexável.

## 7. Roadmap incremental

1. **M1 — Store + schema**: `IntelDataSourceConfig`, bootstrap, repositórios, migração de
   geo nome→IBGE. Sem ingestão ainda; popular via seed manual para validar queries.
2. **M2 — Ingestão PNCP**: `PncpResultadosClient`, `ParticipacaoIngestionService`, backfill,
   scheduler cron-gated, enriquecimento de empresa.
3. **M3 — API de consulta**: 3 endpoints + cache Redis + paginação keyset.
4. **M4 — Performance**: MV `mv_prospeccao` + refresh pós-ETL, particionamento se o volume
   pedir, read replica.

## 8. Riscos / decisões abertas

- **Schema bootstrap vs Flyway**: o projeto evita Flyway de propósito (SIGTAP usa
  `CREATE TABLE IF NOT EXISTS` em runtime). Para um schema Postgres que vai evoluir,
  **recomendo introduzir Flyway escopado a este módulo** — caso contrário, manter o idioma
  do SIGTAP e versionar DDL à mão. Decisão do time.
- **Cobertura de fornecedor por portal**: só o PNCP/ComprasNet expõe `/resultados` de forma
  estável. BLL/BNC/LicitaNet podem não publicar proponentes via API — nesses, o grafo fica
  restrito ao que o portal liberar (documentar lacuna por portal).
- **LGPD**: CNPJ é dado público (Lei 14.133/PNCP), mas o produto de prospecção B2B deve
  registrar finalidade e base legal de tratamento.
```
