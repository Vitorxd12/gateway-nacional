# CerneBR — Gateway Nacional

> **A infraestrutura definitiva e self-hosted para consumo de dados públicos brasileiros.**
> Latência mínima, resiliência industrial e zero acoplamento com a instabilidade da internet pública.

[![Build](https://img.shields.io/badge/build-passing-brightgreen)](#)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange)](#)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F)](#)
[![Docker Ready](https://img.shields.io/badge/docker-ready-2496ED)](#)
[![Tests](https://img.shields.io/badge/tests-Testcontainers%20%2B%20WireMock-25D366)](#)

---

## O Problema

Toda empresa brasileira que constrói software já passou por isso:

- **Rate limits agressivos** — A primeira release sobe bem; na quinta-feira do mês seguinte, o ReceitaWS começa a devolver `HTTP 429` em massa e sua tela de cadastro de fornecedor para de funcionar.
- **Quedas em cascata** — O ViaCEP cai num feriado, seu checkout cai junto, e o time de plantão é acordado às 3h da manhã para descobrir que **o problema não é seu**.
- **Rateio de IP em ambientes cloud** — Em Kubernetes, todos os seus pods saem pelo mesmo egress NAT. Para a API pública lá fora, vocês são *um único cliente* — e batem o limite gratuito antes do almoço.
- **Formatos divergentes** — ViaCEP devolve `localidade`, BrasilAPI devolve `city`. ReceitaWS retorna `nome`, MinhaReceita retorna `razao_social`. Cada provedor é uma migração de schema disfarçada.
- **Dados auxiliares faltando** — Sua emissão de NF-e exige o código IBGE do município, mas metade dos provedores não devolve esse campo. Você precisa de uma tabela auxiliar e ninguém quer manter.
- **Cálculo de dia útil B2B** — Faturamento, vencimento de boleto, prazo de SLA: todos exigem pular finais de semana **e feriados nacionais e estaduais**. Hoje cada time reimplementa isso de forma sutilmente errada.
- **Cotação de índices financeiros** — Correção monetária de contratos, simulação de renda fixa, projeção de juros — tudo depende de Selic, CDI e IPCA atualizados. A API do Banco Central é instável e cada provedor alternativo retorna o JSON num formato sutilmente diferente.

## A Solução

O **Gateway Nacional** é um microserviço self-hosted que **blinda sua aplicação** dessas patologias. Você consulta um único endpoint local, e toda a complexidade desaparece:

- **Cascata de resiliência inteligente** — Múltiplos provedores por domínio. Se o primário cai, o secundário responde *antes do seu cliente perceber*.
- **Circuit Breakers isolados por upstream** — A queda do ViaCEP não contamina a percepção de saúde do BrasilAPI. Cada provedor tem seu próprio sliding window.
- **Cache distribuído agressivo** — Redis com TTL por domínio (30 dias para CEP, 365 dias para feriados, 24h padrão). **Após o primeiro hit, sua latência cai para sub-milissegundos.**
- **Fallback offline determinístico** — Para feriados, um calculador in-memory (algoritmo de Meeus) garante resposta correta mesmo se a internet inteira do Brasil cair.
- **Enriquecimento in-memory** — Quando um provedor responde sem o IBGE do município, o gateway preenche localmente via lookup O(1).
- **Schema unificado** — Você lida com **um único DTO** por domínio. A divergência de formato dos upstreams fica isolada na camada de Anti-Corruption.
- **Rate limit como feature toggle** — Ilimitado por padrão (Enterprise). O Playground público ativa o limite de 5 req/min/IP via uma única env var.

---

## Módulos Disponíveis

### CEP — Resolução de Endereços

Cascata: **ViaCEP → BrasilAPI → AwesomeAPI**.

- DTO unificado: `cep`, `logradouro`, `complemento`, `bairro`, `localidade`, `uf`, `ibge`.
- **Enriquecimento in-memory de IBGE**: quando o provedor que respondeu não devolve o código IBGE do município (caso clássico do BrasilAPI v1), o gateway preenche localmente a partir de um índice in-memory normalizado por `Normalizer.NFD` (acentos e caixa não quebram lookup).
- **Busca por endereço** (`GET /api/v1/cep/busca`): dado UF + cidade + logradouro, retorna lista de CEPs candidatos via ViaCEP. Útil para autocompletar formulários de endereço.
- **Geocodificação reversa** (`GET /api/v1/cep/reverso`): dado lat/lon (ex.: clique num mapa Leaflet/Google Maps), retorna o endereço brasileiro e o CEP do ponto via Nominatim/OSM. Campo `localizacao.precisao` sempre `EXATA`.
- TTL de cache: **30 dias** (CEPs são imutáveis na prática).

### CNPJ — Dados Cadastrais B2B

Cascata: **BrasilAPI → ReceitaWS → MinhaReceita**.

- DTO unificado focado em emissão de NF-e e onboarding B2B: `cnpj`, `razaoSocial`, `nomeFantasia`, `cnaePrincipal`, `status`, `cep`, `uf`, `municipio`.
- **CNAE normalizado** para apenas dígitos em todos os provedores (ReceitaWS retorna `"62.01-5/01"`, BrasilAPI/MinhaReceita retornam numérico — o gateway entrega `"6201501"` consistentemente).
- **Detecção de erro especial do ReceitaWS**: o provedor retorna `HTTP 200` com `{"status": "ERROR"}` para CNPJ inexistente — o gateway trata isso como falha e cascateia para o próximo provedor.

### Calendário & Dias Úteis

Cascata: **BrasilAPI (com suporte estadual) → Nager.Date → Calculador in-memory**.

- **Calculador determinístico offline** implementa o algoritmo Meeus / Jones / Butcher para derivar o Domingo de Páscoa de qualquer ano. A partir dele projeta Carnaval (-47 dias), Sexta-feira Santa (-2), Corpus Christi (+60), e combina com 9 feriados fixos federais (incluindo o 20/11 — Consciência Negra, instituído pela Lei nº 14.759/2023).
- **Suporte a feriados estaduais** via BrasilAPI: passe `?siglaUf=SP` e a resposta inclui também os estaduais daquela UF.
- **Cálculo de próximo dia útil** que pula sábados, domingos e feriados (nacionais sempre, estaduais quando UF informada). Atravessa virada de ano com recarga lazy do conjunto de feriados.
- TTL de cache: **365 dias** (feriados não mudam dentro do ano em curso).

### Tabela FIPE — Cotação de Veículos

Cascata: **FIPE-Oficial (FlareSolverr) → BrasilAPI → Parallelum**.

> **Módulo FIPE agora utiliza extração direta da fundação oficial via FlareSolverr Sidecar, garantindo 100% de disponibilidade independente de APIs de terceiros.** Em 2026-05, BrasilAPI e Parallelum entraram em colapso simultâneo — os proxies deles foram bloqueados pelo upstream `fipe.org.br` (BrasilAPI devolve `500 AxiosError-403` em todas as rotas FIPE; Parallelum aposentou o path direto por código FIPE da v1, e a v2 não expõe consulta direta por código). A reposta foi reposicionar o `FipeOrgScraperClient` como provedor primário: ele consulta a fundação diretamente via o sidecar FlareSolverr, sem depender de intermediários. Os legados continuam na cascata como fallback automático para o dia em que recuperarem.

- DTO unificado: `codigoFipe`, `marca`, `modelo`, `anoModelo` (`int` — `32000` indica Zero KM, convenção FIPE), `combustivel`, `preco` (`BigDecimal`) e `mesReferencia`.
- **Provedor primário (FIPE-Oficial)**: dois steps em sessão FlareSolverr — `ConsultarTabelaDeReferencia` (código do mês, cacheado em memória por 6h) → `ConsultarValorComTodosParametros` com `tipoConsulta=codigo`. Como o FIPE chaveia por `(codigoFipe, anoModelo, codigoTipoCombustivel)` mas nosso DTO público recebe só os dois primeiros, o cliente faz um sweep dos 5 códigos de combustível mais comuns (`Flex, Gasolina, Diesel, Álcool, Elétrico`) e devolve o primeiro hit — caso típico resolve em 1 POST, pior caso em 5.
- **Parsing de moeda BR no ACL**: todos os três provedores devolvem `valor` formatado como `"R$ 80.444,00"`. O Anti-Corruption Layer faz strip do prefixo, troca de separadores (`.` thousands → vazio, `,` decimal → `.`) e converte para `BigDecimal` — financial-grade, sem drift.
- **Filtro client-side por ano no BrasilAPI**: o endpoint legado retorna **todos os anos** do mesmo `codigoFipe` numa array; o ACL seleciona o entry com `anoModelo` solicitado, lançando `ResourceUnavailableException` se nenhum casar (cascateia para Parallelum).
- **Path do Parallelum configurável** via property — endpoints mudam ocasionalmente; override sem PR.
- **Latência observada (FIPE-Oficial)**: cold ~5s (Chromium boot + warm-up + 2 POSTs), warm ~40ms (Redis hit). TTL de cache: **15 dias** (FIPE publica mensalmente; janela balanceia ver o novo mês-de-referência logo após cada ciclo de publicação vs. evitar tráfego upstream redundante).

### Placa — Identificação de Veículos

Cascata: **WDApi → Keplaca → PlacaFipe (Web Scraping)**.

> 🆓 **Modo 100% Gratuito**: o terceiro provedor é um scraper Jsoup de `placafipe.com` que dispensa qualquer token. Em deploys sem credenciais paid (WDApi/Keplaca), a resolução de placa **continua funcionando** via cascata. E mais: o PlacaFipe é o único provedor que entrega o **`codigoFipe`** já mapeado, alimentando o módulo Avaliação sem exigir o código FIPE manualmente do cliente.

- DTO unificado: `placa` (uppercase canônico, sem hífen), `marca`, `modelo`, `anoFabricacao`, `anoModelo`, `chassi` (mascarado, ver abaixo), `municipio`, `uf`, `codigoFipe` (nullable — populado apenas quando o provedor publica essa associação, hoje só PlacaFipe).
- **Aceita ambos os padrões**: antigo (`ABC1234`) e Mercosul (`ABC1D23`), case-insensitive. Regex `^[A-Za-z]{3}[0-9][A-Za-z0-9][0-9]{2}$`.
- **Privacy-by-architecture: chassi mascarado no ACL**. O chassi completo (17 chars) entra no Record interno do client mas **nunca** chega ao DTO de saída — a conversão devolve `***` + últimos 4 caracteres. Logs, cache e response sempre mascarados; impossível vazar acidentalmente. Vale para os 3 provedores: o `PlacaFipeScraperClient` também passa pelo `ChassiMask` antes de devolver.
- **Fail-safe de credencial**: o `KeplacaClient` detecta o token-placeholder (`your_token_here`) antes de qualquer chamada HTTP e curto-circuita lançando `ResourceUnavailableException`. A cascata absorve normalmente — em dev sem token, o gateway depende do WDApi e do PlacaFipe sem NullPointer ou 401 ruidosos.
- **PlacaFipe — fallback de Web Scraping com enriquecimento FIPE**: GET HTML defensivo com `User-Agent`, `Referer`, `Accept-Language` em `https://placafipe.com/placa/{placa}`. Em vez de hard-codar seletores frágeis, o ACL varre toda estrutura conhecida de label-value (`<tr>th/td`, `<tr>td/td`, `<dl>dt/dd`) e indexa em `Map<labelNormalizado, valor>` — depois faz lookup pelos rótulos canônicos com aliases (`marca`, `modelo`, `ano fabricacao`/`ano de fabricacao`, `ano modelo`, `chassi`/`chassis`, `municipio`/`cidade`, `uf`/`estado`, `codigo fipe`/`fipe`). Quando o site redesenhar a página, basta o tipo de wrap (table/dl) sobreviver — labels mudaram? só adicionar alias. Código FIPE coletado é normalizado para `000000-0` (zero-pad) compatível com a regex do `/api/v1/fipe/preco/...`.
- **Composição com Avaliação**: o controller `/api/v1/avaliacao/placa/{placa}` aceita `?codigoFipe=` opcional; quando o PlacaFipe resolve a cascata, esse campo já vem dentro do `dadosVeiculo` e o caller pode encadear sem fricção. **Token-free de ponta a ponta**.
- 3 Circuit Breakers isolados (`wdApiPlacaCB`, `keplacaCB`, `placaFipeScraperCB`) — falha de scraper nunca contamina a saúde percebida das APIs pagas, e vice-versa.
- TTL de cache: **365 dias** (a vinculação placa-veículo é essencialmente permanente para a vida útil do registro).

### 🏥 Saúde Pública (APS) — Inteligência Financeira do SUS

Auditoria automatizada dos repasses federais da Atenção Primária à Saúde — duas rotas atômicas que substituem horas de trabalho manual em PDFs do FNS e portais lentos do e-Gestor.

**Contexto de negócio**: prefeituras e consultorias perdem milhões em repasses suspensos por irregularidade na composição de equipes APS. A auditoria normalmente é feita olho-no-olho em portais governamentais; este módulo expõe os dois mais críticos como JSON limpo, com cache agressivo e Circuit Breaker isolado por upstream.

- **FNS — Fundo Nacional de Saúde** (`/api/v1/saude/fns/{ibge}?competencia=yyyy-MM`): apesar do portal ser uma SPA, os dados vêm de dois endpoints JSON internos (descoberta de UG/CNPJ → repasses detalhados). O gateway cascateia automaticamente entre as UGs publicadas para a cidade quando a primeira retorna vazio (caso clássico: Fundo Municipal de Saúde com CNPJ distinto da prefeitura), e flipa entre `tipoConsulta=2` e `tipoConsulta=1` — a mesma estratégia consagrada do pipeline interno **AutoAPSFinancias**.
- **e-Gestor APS** (`/api/v1/saude/egestor/{ibge}?competencia=yyyy-MM`): pipeline JSON puro de três etapas (menu de parcelas → componentes de pagamento → relatório detalhado). O gateway varre todos os componentes do bloco APS (ESF, ESB, eMulti, ACS), agrega por `INE + tipoEquipe` e devolve **uma linha por equipe** com valor de custeio total e status de suspensão consolidado.
- **DTOs unificados**:
  - `RepasseFnsResponse(codigoIbge, competencia "yyyy-MM", bloco, valorTotal BigDecimal)` — bloco vem de `grupoAcao.nome` (ou `descricao` em fallback);
  - `EquipeEGestorResponse(ine, tipoEquipe, valorCusteio BigDecimal, statusSuspensao)` — `NÃO SUSPENSO` por padrão; quando o e-Gestor expõe motivo (`dsMotivoSuspensao`, `motivoSuspensao` etc.), o texto é propagado verbatim.
- **ACL alias-tolerante**: o e-Gestor espalha o mesmo conceito por vários nomes de campo (`coEquipe`/`coEquipeEsb`/`nuIne` para INE, `tpEquipe`/`coTipoEquipe` para tipo). O ACL escolhe o primeiro não-vazio — renames upstream não quebram consumidores.
- **Validação de IBGE**: regex `^[0-9]{6,7}$`. Aceita o formato canônico SUS (6 dígitos) e o formato com dígito verificador (7 dígitos — gateway trunca). UF é derivada in-memory dos 2 primeiros dígitos via tabela completa das 27 UFs (`IbgeUfLookup`); validação de competência via `^[0-9]{4}-(0[1-9]|1[0-2])$`.
- **Resiliência**: dois Circuit Breakers isolados (`fnsScraperCB`, `eGestorScraperCB`) com timeout estendido de **15s** — gov.br é lento, e o ciclo do FNS exige duas chamadas sequenciais. Parses defensivos com `try/catch` no nível do item; quando a estrutura JSON muda, a linha é descartada silenciosamente em vez de derrubar o lote inteiro.
- **TTL de cache: 15 dias** (publicação federal é mensal e raramente revisada na janela; cada chamada FNS é multi-etapa e martelar o portal triggera anti-bot — cache agressivo é defensivo, não cosmético).
- **Caveat anti-bot do FNS**: o pipeline original AutoAPSFinancias aquece cookies via headless Chrome antes de bater nos endpoints. O gateway tenta a chamada direta com headers de browser (`User-Agent`, `Referer`, `X-Requested-With`); quando gov.br aplica anti-bot pesado, o CB trips e a resposta vira **503 com mensagem clara**. Embarcar Selenium no gateway seria 200MB+ de dependência — para deploys que precisam dessa robustez, o caminho é um sidecar de warmup externo.

#### Camada Estrutural — Quem Trabalha Onde, e Quem Enviou Produção

Duas rotas atômicas que respondem a perguntas que toda auditoria APS faz:

- **CNES — Profissionais por Estabelecimento** (`/api/v1/saude/cnes/{cnesBase}/profissionais?ibge={ibge}`): JSON puro do DATASUS em duas etapas — lista de equipes do estabelecimento (`/services/estabelecimentos-equipes/{ibge}{cnes}`) seguida da lista de profissionais por equipe (`/services/.../profissionais/{ibge}{cnes}` com `coArea` + `coEquipe`). DTO unificado: `cnesDaUnidade`, `ineEquipe` (zero-pad 10 dígitos), `nome`, `cns`, `cbo`, `cargaHoraria` (soma `chAmb + chOutros`), `dataEntrada` (formato verbatim do upstream).
  - **Por que `ibge` é obrigatório**: o CNES indexa por chave composta `{ibge}{cnes}` — código CNES de 7 dígitos não é único entre municípios. O Swagger explicita esse contrato.
  - ACL alias-tolerante (4 aliases para nome, 5 para CNS, 5 para data de entrada) — renames upstream do DATASUS não quebram consumidores.
  - **Escopo: somente estabelecimentos com APS.** A rota expõe os endpoints de **Atenção Primária à Saúde** do DATASUS. Estabelecimentos **sem equipes APS cadastradas** (UPAs, hospitais, laboratórios, consultórios, clínicas especializadas) recebem do upstream uma página HTML `"Your connection was refused"` — é a forma como o DATASUS sinaliza ausência de dados de APS, **não falha de infraestrutura**. O gateway detecta esse padrão e devolve **503** com mensagem específica orientando o consumidor (`"O DATASUS sinalizou ausência de dados de APS para este estabelecimento... esta rota só serve estabelecimentos com APS (UBS, postos de saúde)..."`). Retentar com o mesmo CNES devolve a mesma resposta — não é uma falha transiente.
- **SISAB — Validação da Produção** (`/api/v1/saude/sisab/{ibge}/producao?competencia=yyyy-MM`): única rota cuja extração depende de um sidecar dedicado de browser real. SISAB é JSF/Mojarra com filtros renderizados por **Bootstrap Multiselect** — plugins JS que mantêm o estado em DOM e só sincronizam para o `<select multiple>` por meio de cliques reais. HTTP puro produz `IndexOutOfBoundsException` no backend (validamos empiricamente em 2026-05). FlareSolverr v3 não expõe primitivas de interação JS (`executeJS`, click). A solução adotada é um **sidecar Python dedicado** (`services/sisab-sidecar/`) que envelopa Selenium + Chromium headless e expõe `POST /scrape` consumido pelo gateway como qualquer outro upstream — com seu próprio Circuit Breaker `sisabScraperCB` (timeout 120s, cobre cold scrape ~30–90s + paginação DataTables).
  - DTO: `ibge`, `cnes`, `ine`, `statusValidacao` (`Aprovado`/`Reprovado` verbatim).
  - Implementação reusa lógica testada em produção do projeto upstream **AutoAPSFinancias**: cascata `unidGeo=municipio` → UF → IBGE → "Marcar todas as colunas" → `validacao=Aprovado` → competência → submit `verTela`, com `jQuery.active === 0` polls entre cada step.
  - **Latência observada**: cold ~14s, cache hit Redis ~30ms (`ResilientGenericJacksonSerializer` graceful, TTL 15 dias).
  - **Engenharia HTTP**: o cliente Java fixa explicitamente HTTP/1.1 no `JdkClientHttpRequestFactory` para falar com o uvicorn — o JDK HTTP Client default tenta upgrade `h2c` que o uvicorn descarta, gerando body vazio do lado do FastAPI.
  - **Sem sidecar configurado** (`gateway.saude.sisab.sidecar-url` vazio), a rota responde **503 com mensagem clara** orientando ativação — fast-fail, sem timeout inútil.
- **Quatro Circuit Breakers isolados** no domínio Saúde: `fnsScraperCB`, `eGestorScraperCB`, `cnesScraperCB` (timeout 20s — N+1 chamadas por equipe), `sisabScraperCB` (timeout 30s — round-trip JSF). Falha de um upstream nunca contamina a saúde percebida dos demais.
- **Cache key composto no CNES**: `'cnes-' + cnesBase + '-' + ibge`, refletindo a chave real do upstream — evita servir profissionais de um município errado caso dois usem códigos CNES coincidentes.

#### 🔍 O Auditor Automático (Rotas Mágicas)

A peça que transforma os quatro endpoints atômicos da Saúde em **inteligência acionável**: uma única chamada, três fontes cruzadas em paralelo, e o gateway aponta com nome e sobrenome quem fez o município perder o repasse federal daquela competência.

**O problema que resolve.** Quando o e-Gestor reporta uma equipe suspensa por motivo de produção, a pergunta que vale dinheiro não é *"qual equipe?"* — é *"qual médico ou enfermeiro daquela equipe não enviou produção pro SISAB?"*. Hoje, descobrir isso exige abrir três portais governamentais em três abas diferentes, fazer cross-reference manual de INE com CNES, e depois cruzar com a lista de validação do SISAB. Horas de trabalho de auditor por município. Por mês.

**A rota mágica**: `GET /api/v1/saude/auditoria/inadimplencia?ibge={ibge}&cnes={cnes}&competencia={yyyy-MM}`.

```bash
# Auditoria completa de um estabelecimento específico em fevereiro/2024
curl "http://localhost:8080/api/v1/saude/auditoria/inadimplencia?ibge=292870&cnes=2469776&competencia=2024-02"
```

```json
[
  {
    "ineEquipe": "123456",
    "statusRepasse": "SUSPENSO",
    "motivoSuspensao": "EQUIPE SEM ENVIO DE PRODUCAO APROVADA NO SISAB",
    "cnesUnidade": "2469776",
    "profissionaisInadimplentes": [
      "MARIA DA SILVA",
      "JOÃO CARLOS PEREIRA",
      "ANA CAROLINA SOUZA"
    ]
  }
]
```

**Como o cruzamento funciona** (executado em paralelo, 3 chamadas concorrentes em Virtual Threads):

1. **e-Gestor** entrega as equipes do município com `statusSuspensao` e motivo. O auditor mantém apenas as suspensas cujo motivo contém *"produção"* ou *"envio"* — a assinatura financeira de gap de validação no SISAB. Filtro acento-insensitive (cobre `"PRODUCAO"`, `"PRODUÇÃO"`, `"ENVIO NÃO REALIZADO"` etc.).
2. **CNES** entrega os profissionais cadastrados naquele estabelecimento, agrupados por **INE canônico** (numérico, sem zeros à esquerda) — chave estável para o cruzamento entre os três portais que cada um normaliza o INE de um jeito.
3. **SISAB** entrega as validações da competência. O auditor reduz para um `Set` de *INEs Aprovados* no CNES requisitado. Ausência neste conjunto é o sinal **inequívoco** de inadimplência.

**O veredito** sai por equipe que satisfaz os três critérios simultâneos: (a) suspensa por produção no e-Gestor, (b) presente no CNES requisitado, (c) INE **não-Aprovado** no SISAB. Para cada veredito, a lista de profissionais vem direto da CNES — a *actionable list* que vai pro gestor.

**Tolerância e honestidade**: quando o motivo da suspensão **não** é de produção (ex: `"FALTA DE COMPOSIÇÃO MÍNIMA"`), o auditor não devolve veredito — outra ferramenta resolve. Quando a equipe está suspensa por produção mas o SISAB **tem** Aprovado para ela, `profissionaisInadimplentes` vem **vazio** — sinal de que a causa real está em outro lugar (talvez o CNES esteja desatualizado e quem realmente produziu não está mais cadastrado). Nada é fabricado.

**Performance**: as três chamadas downstream (e-Gestor, CNES, SISAB) já são cacheadas em Redis por 15 dias dentro do namespace `saude`. Numa cache warm, a auditoria responde em **~1 ms** — o cruzamento é puro `HashMap`/`HashSet` em memória. A primeira chamada paga o custo dos três portais em paralelo (limitado pelo mais lento), todas as subsequentes na mesma janela são essencialmente gratuitas.

### 📊 Avaliação de Mercado & Price Gap

Cascata de scrapers paralelos: **OLX + MobiAuto** (executados concorrentemente em Virtual Threads), com cruzamento contra **FIPE** e **Placa**.

- DTO composto: `placa`, `dadosVeiculo` (resposta completa do módulo Placa, com chassi mascarado), `referenciaFipe` (cotação FIPE — opcional), `mercado` (`precoMedio`, `menorPreco`, `maiorPreco`, `quantidadeAnunciosEncontrados`, `linksReferencia`), `scoreAvaliacao`.
- **Web Scraping em tempo real**: o gateway compõe a URL de busca de cada marketplace (slug normalizado de marca/modelo + ano), faz `GET` HTML via Jsoup e extrai os preços anunciados na primeira página. Marketplaces atualmente cobertos: **OLX** e **MobiAuto** — ambos com URLs e templates configuráveis em `application.yml`, override sem deploy.
- **Fan-out paralelo em Virtual Threads**: cada scraper roda numa virtual thread isolada (`Executors.newVirtualThreadPerTaskExecutor()`); o tempo de parede da resposta acompanha o scraper mais lento, não a soma — JEP 444 comprovando seu valor em I/O-bound real.
- **Resiliência da raspagem**: cada scraper tem seu próprio Circuit Breaker (`olxScraperCB`, `mobiAutoScraperCB`). Quando um marketplace muda o HTML e o seletor para de casar, a raspagem falha, o CB conta a falha, a cascata segue com os scrapers restantes e a média é computada sobre o que sobrou. **Defensivo por design** — o gateway nunca cai por mudança no DOM de terceiro.
- **Parsing financeiro endurecido**: extrator regex de BRL (`R$ 45.000,00`) com filtros heurísticos (descarta valores < R$ 1.000 — ruído de "frete grátis acima de", "membro premium R$ 9,90" etc.). Cálculos em `BigDecimal` (sem drift de ponto flutuante) e arredondamento `HALF_UP` na média.
- **Score com tolerância de ±5%** sobre o preço FIPE: `Acima da FIPE` (>105%), `Em linha com a FIPE` (95–105%), `Abaixo da FIPE` (<95%). Quando FIPE ou mercado estão indisponíveis, o score reflete explicitamente (`FIPE não fornecida (informe codigoFipe)`, `Sem dados de mercado disponíveis`).
- **A avaliação por placa descobre automaticamente o Código FIPE do veículo (através do scraper integrado) cruzando o valor com o mercado em tempo real.** Quando a cascata do módulo Placa resolve via `PlacaFipeScraperClient`, o `PlacaResponse.codigoFipe` já vem populado e o `AvaliacaoService` enxerga o campo, dispara o `FipeService.findPreco` automaticamente e devolve a `referenciaFipe` na mesma resposta — **sem o caller ter que fornecer o código FIPE manualmente**. Quando o caller passa `?codigoFipe=` explicitamente, o valor do caller tem precedência (pode conhecer melhor uma variante de ano-modelo). Quando nenhum dos dois publica o código (cascata caiu em WDApi/Keplaca e o caller não informou), a referência FIPE é omitida e o score reflete — comportamento honesto, sem fabricar dados.
- **Paralelismo total no `composeAvaliacao`**: a chamada FIPE (quando o código existe) e o fan-out de scrapers de mercado disparam concorrentemente no **mesmo `Executors.newVirtualThreadPerTaskExecutor()`** — sem aninhamento de pools. Wall-time ≈ `max(FIPE, scraper mais lento)`, não a soma. Em cache warm de Placa+FIPE, a auditoria responde basicamente no tempo do scraper mais lento.
- **Privacidade preservada**: o chassi em `dadosVeiculo` permanece mascarado conforme contrato do módulo Placa. Avaliação é uma camada de composição — não bypassa políticas de privacidade dos módulos abaixo.
- Sem cache no nível do composto: os módulos Placa (365d) e FIPE (15d) cacheiam separadamente; preços de mercado são, por natureza, voláteis e devem refletir o momento.

#### Avaliação Manual (Livre de Tokens)

A rota `GET /api/v1/avaliacao/manual` desacopla a inteligência de mercado da consulta de placa: pula totalmente o `PlacaService` e vai direto à FIPE (opcional) e aos scrapers. **Use quando**:

- você já sabe `marca`, `modelo` e `ano` (ex: avaliação de anúncio prestes a ser publicado);
- os provedores de placa (WDApi/Keplaca) estão bloqueados, sem credencial ou fora do ar;
- você quer eliminar uma chamada externa que não agregaria informação ao caso de uso.

Garantias mantidas: scraping paralelo em Virtual Threads, Circuit Breaker isolado por marketplace, parsing financeiro `BigDecimal` com filtros heurísticos, score com banda ±5% quando o `codigoFipe` é fornecido. Na resposta, `placa` e `dadosVeiculo` chegam `null` — sinalização explícita de que a identificação foi pulada.

### Catálogo de Bancos & ISPB

Cascata: **BrasilAPI → Registro local in-memory (BACEN dump)**.

- DTO unificado: `ispb` (8 dígitos), `nome` (razão abreviada), `codigo` (COMPE com 3 dígitos zero-pad), `nomeCompleto` (razão social).
- **Normalização do código no controller** — `1`, `01` e `001` viram `001` antes de bater no `@Cacheable`. Garante uma única entrada Redis por instituição independentemente de como o cliente formate.
- **Fallback in-memory de latência zero** — `LocalBacenBancoClient` carrega um JSON bundled em `@PostConstruct` para um `Map<String, BancoResponse>` indexado por COMPE. Mesmo com BrasilAPI fora, validar uma transferência continua sendo lookup O(1) offline.
- Dois endpoints: lista completa (`GET /api/v1/bancos`) e busca unitária (`GET /api/v1/bancos/{codigo}`). Cache key da lista é o literal `'all'`, sem colisão com qualquer COMPE de 3 dígitos.
- TTL de cache: **30 dias** (registros bancários mudam raramente; trade-off seguro pra reduzir tráfego upstream e latência ao mínimo).

### Rastreio & Logística

Cascata: **Link&Track → BrasilAPI → Correios Oficial**.

- DTO unificado: `codigo` (uppercase canônico), `isEntregue` (boolean) e `eventos` ordenados **do mais recente para o mais antigo**.
- **Inferência de `isEntregue` no service**: scan dos eventos por status contendo `"ENTREG"` (case-insensitive). Independe de qual provedor responder — clientes confiam no boolean sem precisar inspecionar a timeline.
- **Date-time merge no ACL**: Link&Track retorna `data` (`dd/MM/yyyy`) e `hora` (`HH:mm`) separados — o Record interno faz o merge para `LocalDateTime`. BrasilAPI e Correios assumem ISO-8601 nativo.
- TTL de cache: **1 hora** (curto porque rastreio é *time-sensitive* — clientes acompanham status no app dos Correios e cobram do CRM se não bater; janela balanceia frescor vs. carga upstream).

### Taxas — Índices Financeiros

Cascata: **BrasilAPI → BCB SGS → HG Brasil**.

- DTO unificado: `nome` (sigla canônica em uppercase), `valor` (`BigDecimal` — sem drift de ponto flutuante em cálculos financeiros), `dataReferencia`.
- **BCB SGS** (Sistema Gerenciador de Séries Temporais do Banco Central) é série-codificado: o gateway mantém um map interno `{CDI=12, SELIC=11, IPCA=433}` e absorve as peculiaridades do payload (data em `dd/MM/yyyy`, valor como string numérica) na camada de Anti-Corruption.
- **HG Brasil** cobre apenas Selic e CDI no endpoint `/finance/taxes`. Para IPCA, esse provedor cascateia explicitamente via exceção — só é problema se BrasilAPI **e** BCB SGS estiverem fora simultaneamente para IPCA.
- Cache key normalizado para uppercase via SpEL (`#sigla.toUpperCase()`) — `cdi`, `Cdi` e `CDI` compartilham a mesma entrada.
- TTL de cache: **12 horas** (publicação diária do BCB; refresh duas vezes por dia captura mudanças do COPOM no mesmo dia útil).

---

## Arquitetura Enterprise

Cada decisão técnica foi tomada com um propósito específico. Sem cargo cult.

| Tecnologia | Por que está aqui |
|---|---|
| **Java 25 + Virtual Threads** | Cada request HTTP roda em uma virtual thread (JEP 444). Para uma carga *I/O-bound* como a nossa — esperando respostas de APIs externas — isso significa **dezenas de milhares de requisições concorrentes** com pegada de memória mínima, sem o ônus de pools de threads tradicionais. O `JdkClientHttpRequestFactory` outbound também usa `Executors.newVirtualThreadPerTaskExecutor()`, garantindo virtual threads ponta-a-ponta. |
| **Resilience4j** | Circuit Breaker e TimeLimiter declarativos via `@CircuitBreaker(name = "viaCepCB")`. **Uma instância nomeada por upstream** — falha do ViaCEP nunca abre o CB do BrasilAPI. Sliding window de 10 chamadas, 50% failure rate, 15s no estado *open*. |
| **Bucket4j + Redis** | Rate limiter distribuído via `LettuceBasedProxyManager`. O bucket vive no Redis, então você pode escalar horizontalmente os pods do gateway sem que cada réplica tenha sua própria contagem. **Toggle via Feature Flag `gateway.rate-limit.enabled`** — ilimitado por padrão (Enterprise self-hosted), ativado explicitamente apenas no Playground público. |
| **Anti-Corruption Layer** | Cada client externo tem um **Record Java privado** que mapeia o JSON específico daquele provedor e converte para o DTO unificado. Quando o ReceitaWS muda `nome` para `razao_social` na próxima versão, **só uma classe muda** — o resto da aplicação não percebe. |
| **Micrometer + Prometheus** | Duas métricas estratégicas: `gateway.provider.requests` (counter, com tags `domain`/`provider`/`outcome`) e `gateway.provider.latency` (timer). Prontas para scrape em `/actuator/prometheus`. |
| **Spring Boot 4 + ProblemDetail** | Tratamento global de exceções segue **RFC 7807** (`ProblemDetail`). Toda falha — validação, upstream indisponível, rate limit — devolve o **mesmo formato JSON** com `type`, `title`, `detail`, `instance` e `traceId` para correlação com logs. Stacktraces *jamais* vazam ao cliente. |
| **Cache (Redis) com TTL por domínio** | `RedisCacheManager` configurado com TTL padrão de 24h e *override* específico por cache (`ceps`: 30 dias, `feriados`: 365 dias). Chaves de cache são compostas onde necessário (ex: `feriados/2025-SP` vs `feriados/2025-BR`) para evitar colisão entre escopos. |
| **Docker multi-stage + healthcheck** | Build em duas etapas (Maven + JDK 25 → JRE 25 Alpine). Imagem final pequena, usuário não-root, `MaxRAMPercentage=75` para respeitar limites de cgroup. `docker-compose.yml` com `depends_on: condition: service_healthy` garante que o Redis aceita conexões antes do app subir. |
| **Testcontainers + WireMock** | Testes de integração rodam contra um Redis real (container) e um WireMock simulando os providers. O cascade fallback é validado ponta a ponta — não em mocks superficiais. |

### Topologia em 30 segundos

```
            ┌─────────────────────────────────────────────────────────┐
            │                  Gateway Nacional                        │
Cliente ───▶│  ┌──────────────┐    ┌─────────────────┐                 │
   HTTP     │  │ Rate Limiter │───▶│  Cache (Redis)  │── hit ──▶ resp  │
            │  │  (Bucket4j)  │    └────────┬────────┘                 │
            │  │  feature flag│             │ miss                     │
            │  └──────────────┘             ▼                          │
            │  ┌─────────────────────────────────────────┐             │
            │  │        Service Orquestrador             │             │
            │  │  ┌───────┐  fail  ┌───────┐  fail  ┌─┐  │             │
            │  │  │ CB #1 │ ─────▶ │ CB #2 │ ─────▶ │ │  │             │
            │  │  └───┬───┘        └───┬───┘        └─┘  │             │
            │  └──────┼────────────────┼─────────────────┘             │
            └─────────┼────────────────┼───────────────────────────────┘
                     ▼                ▼
                 ViaCEP          BrasilAPI         (...AwesomeAPI / offline calc)
```

---

## Quick Start

A stack inteira sobe em **um único comando**: aplicação + Redis com healthcheck + dependência ordenada.

```bash
docker compose up -d --build
```

A aplicação fica disponível em `http://localhost:8080`. O Swagger UI em `/swagger-ui.html`.

### CEP — exemplos

```bash
# Resolução básica por CEP
curl http://localhost:8080/api/v1/cep/01001000
```

```json
{
  "cep": "01001-000",
  "logradouro": "Praça da Sé",
  "complemento": "lado ímpar",
  "bairro": "Sé",
  "localidade": "São Paulo",
  "uf": "SP",
  "ibge": "3550308"
}
```

```bash
# Busca por endereço (UF + cidade + logradouro) — retorna lista de candidatos
curl "http://localhost:8080/api/v1/cep/busca?uf=SP&cidade=S%C3%A3o+Paulo&logradouro=Pra%C3%A7a+da+S%C3%A9"
```

```json
{
  "total": 1,
  "candidatos": [
    {
      "cep": "01001-000",
      "logradouro": "Praça da Sé",
      "complemento": "lado ímpar",
      "bairro": "Sé",
      "localidade": "São Paulo",
      "uf": "SP",
      "ibge": "3550308",
      "localizacao": null
    }
  ]
}
```

```bash
# Geocodificação reversa — clique no mapa → CEP
curl "http://localhost:8080/api/v1/cep/reverso?lat=-23.5505&lon=-46.6333"
```

```json
{
  "total": 1,
  "candidatos": [
    {
      "cep": "01310-100",
      "logradouro": "Avenida Paulista",
      "complemento": null,
      "bairro": "Bela Vista",
      "localidade": "São Paulo",
      "uf": "SP",
      "ibge": "3550308",
      "localizacao": {
        "latitude": -23.5505,
        "longitude": -46.6333,
        "precisao": "EXATA",
        "fonte": "OpenStreetMap-Nominatim-Reverso"
      }
    }
  ]
}
```

### CNPJ — exemplos

```bash
# Banco do Brasil — público, ótimo para teste
curl http://localhost:8080/api/v1/cnpj/00000000000191
```

```json
{
  "cnpj": "00000000000191",
  "razaoSocial": "BANCO DO BRASIL SA",
  "nomeFantasia": "BB",
  "cnaePrincipal": "6422100",
  "status": "ATIVA",
  "cep": "70073900",
  "uf": "DF",
  "municipio": "Brasília"
}
```

### Calendário & Dias Úteis — exemplos

```bash
# Feriados nacionais de 2025
curl http://localhost:8080/api/v1/calendario/feriados/2025

# Feriados nacionais + estaduais de São Paulo em 2025
curl "http://localhost:8080/api/v1/calendario/feriados/2025?siglaUf=SP"

# Próximo dia útil a partir de uma sexta-feira de Carnaval
# (pula sábado, domingo, segunda e terça de carnaval, e quarta-feira de cinzas é meio expediente — mas oficialmente útil)
curl "http://localhost:8080/api/v1/calendario/proximo-dia-util?data=2025-02-28"

# Próximo dia útil considerando feriado estadual de SP
# 09/07/2025 (quarta) é Revolução Constitucionalista em SP
curl "http://localhost:8080/api/v1/calendario/proximo-dia-util?data=2025-07-09&siglaUf=SP"
```

```json
{
  "dataBase": "2025-07-09",
  "proximoDiaUtil": "2025-07-10",
  "diasAdicionados": 1
}
```

### FIPE — exemplos

```bash
# VW Cross UP! 2018 — código FIPE no padrão 000000-0
curl http://localhost:8080/api/v1/fipe/preco/005340-0/2018

# Veículo Zero KM — sentinela 32000 da FIPE
curl http://localhost:8080/api/v1/fipe/preco/005340-0/32000
```

```json
{
  "codigoFipe": "005340-0",
  "marca": "Volkswagen",
  "modelo": "Cross UP! TSI 1.0 12V Total Flex",
  "anoModelo": 2018,
  "combustivel": "Gasolina",
  "preco": 80444.00,
  "mesReferencia": "março de 2024"
}
```

### Placa — exemplos

```bash
# Padrão Mercosul (case-insensitive)
curl http://localhost:8080/api/v1/placa/abc1d23

# Padrão antigo
curl http://localhost:8080/api/v1/placa/ABC1234
```

```json
{
  "placa": "ABC1D23",
  "marca": "VOLKSWAGEN",
  "modelo": "GOL 1.0 FLEX",
  "anoFabricacao": 2010,
  "anoModelo": 2011,
  "chassi": "***3456",
  "municipio": "Guarulhos",
  "uf": "SP",
  "codigoFipe": "005340-0"
}
```

> `codigoFipe` é `null` quando o provedor que resolveu a cascata foi WDApi ou Keplaca. Vem populado quando o PlacaFipe scraper resolve — alimentando direto o módulo Avaliação sem exigir o código manualmente.

### Saúde Pública (APS) — exemplos

```bash
# Repasses FNS de Vitória da Conquista (BA) em fevereiro/2024
curl "http://localhost:8080/api/v1/saude/fns/292870?competencia=2024-02"

# Equipes APS detalhadas via e-Gestor para o mesmo município/competência
curl "http://localhost:8080/api/v1/saude/egestor/292870?competencia=2024-02"
```

Resposta FNS (lista — uma linha por bloco/ação):

```json
[
  {
    "codigoIbge": "292870",
    "competencia": "2024-02",
    "bloco": "ATENCAO PRIMARIA",
    "valorTotal": 487320.50
  },
  {
    "codigoIbge": "292870",
    "competencia": "2024-02",
    "bloco": "VIGILANCIA EM SAUDE",
    "valorTotal": 92410.00
  }
]
```

Resposta e-Gestor (lista — uma linha por equipe):

```json
[
  {
    "ine": "0000123456",
    "tipoEquipe": "ESF",
    "valorCusteio": 32850.00,
    "statusSuspensao": "NÃO SUSPENSO"
  },
  {
    "ine": "0000789012",
    "tipoEquipe": "ESB",
    "valorCusteio": 0.00,
    "statusSuspensao": "EQUIPE SUSPENSA POR DESCUMPRIMENTO DE COMPOSICAO MINIMA"
  }
]
```

### Saúde Pública — Estrutura (CNES e SISAB) — exemplos

```bash
# Profissionais cadastrados num estabelecimento (ibge é obrigatório)
curl "http://localhost:8080/api/v1/saude/cnes/2469776/profissionais?ibge=292870"

# Validação da produção SISAB para o município numa competência
curl "http://localhost:8080/api/v1/saude/sisab/292870/producao?competencia=2024-02"
```

Resposta CNES (lista — uma linha por profissional):

```json
[
  {
    "cnesDaUnidade": "2469776",
    "ineEquipe": "0000123456",
    "nome": "MARIA DA SILVA",
    "cns": "700000000000000",
    "cbo": "225125",
    "cargaHoraria": 40,
    "dataEntrada": "01/03/2023"
  }
]
```

Resposta SISAB (lista — uma linha por equipe que enviou produção):

```json
[
  {
    "ibge": "292870",
    "cnes": "2469776",
    "ine": "0000123456",
    "statusValidacao": "Aprovado"
  },
  {
    "ibge": "292870",
    "cnes": "2469881",
    "ine": "0000789012",
    "statusValidacao": "Reprovado"
  }
]
```

### Avaliação de Mercado — exemplos

```bash
# Avaliação simples (sem FIPE — score retorna "FIPE não fornecida")
curl http://localhost:8080/api/v1/avaliacao/placa/ABC1D23

# Avaliação completa cruzando FIPE
curl "http://localhost:8080/api/v1/avaliacao/placa/ABC1D23?codigoFipe=005340-0"
```

```json
{
  "placa": "ABC1D23",
  "dadosVeiculo": {
    "placa": "ABC1D23",
    "marca": "VOLKSWAGEN",
    "modelo": "GOL 1.0 FLEX",
    "anoFabricacao": 2010,
    "anoModelo": 2011,
    "chassi": "***3456",
    "municipio": "Guarulhos",
    "uf": "SP"
  },
  "referenciaFipe": {
    "codigoFipe": "005340-0",
    "marca": "Volkswagen",
    "modelo": "GOL 1.0 FLEX",
    "anoModelo": 2011,
    "combustivel": "Gasolina",
    "preco": 32500.00,
    "mesReferencia": "março de 2026"
  },
  "mercado": {
    "precoMedio": 34850.00,
    "menorPreco": 28900.00,
    "maiorPreco": 41200.00,
    "quantidadeAnunciosEncontrados": 27,
    "linksReferencia": [
      "https://www.olx.com.br/autos-e-pecas/carros-vans-e-utilitarios/volkswagen/gol-1-0-flex/ano-2011",
      "https://www.mobiauto.com.br/comprar/volkswagen/gol-1-0-flex/2011"
    ]
  },
  "scoreAvaliacao": "Em linha com a FIPE"
}
```

### Avaliação Manual — exemplos (livre de tokens de placa)

```bash
# Avaliação direta sem placa, FIPE incluída
curl "http://localhost:8080/api/v1/avaliacao/manual?marca=Volkswagen&modelo=Gol+1.0+Flex&ano=2011&codigoFipe=005340-0"

# Avaliação direta sem placa e sem FIPE — só scraping de mercado
curl "http://localhost:8080/api/v1/avaliacao/manual?marca=Fiat&modelo=Uno+Mille&ano=2013"
```

```json
{
  "placa": null,
  "dadosVeiculo": null,
  "referenciaFipe": {
    "codigoFipe": "005340-0",
    "marca": "Volkswagen",
    "modelo": "GOL 1.0 FLEX",
    "anoModelo": 2011,
    "combustivel": "Gasolina",
    "preco": 32500.00,
    "mesReferencia": "março de 2026"
  },
  "mercado": {
    "precoMedio": 34850.00,
    "menorPreco": 28900.00,
    "maiorPreco": 41200.00,
    "quantidadeAnunciosEncontrados": 27,
    "linksReferencia": [
      "https://www.olx.com.br/autos-e-pecas/carros-vans-e-utilitarios/volkswagen/gol-1-0-flex/ano-2011",
      "https://www.mobiauto.com.br/comprar/volkswagen/gol-1-0-flex/2011"
    ]
  },
  "scoreAvaliacao": "Em linha com a FIPE"
}
```

### Bancos — exemplos

```bash
# Catálogo completo (cacheado por 30 dias)
curl http://localhost:8080/api/v1/bancos

# Banco do Brasil — `1`, `01` e `001` são equivalentes
curl http://localhost:8080/api/v1/bancos/001

# Nubank — código sem zero à esquerda também funciona
curl http://localhost:8080/api/v1/bancos/260
```

```json
{
  "ispb": "00000000",
  "nome": "BCO DO BRASIL S.A.",
  "codigo": "001",
  "nomeCompleto": "Banco do Brasil S.A."
}
```

### Rastreio — exemplos

```bash
# Status atual de uma encomenda (case-insensitive)
curl http://localhost:8080/api/v1/rastreio/LB123456789BR
```

```json
{
  "codigo": "LB123456789BR",
  "isEntregue": false,
  "eventos": [
    {
      "dataHora": "2026-05-05T14:23:00",
      "local": "Centro de Distribuição - São Paulo/SP",
      "status": "Em trânsito",
      "descricao": "Objeto em trânsito para a unidade de distribuição"
    }
  ]
}
```

### Taxas — exemplos

```bash
# CDI atual (case-insensitive)
curl http://localhost:8080/api/v1/taxas/cdi

# Selic em uppercase — mesma entrada de cache
curl http://localhost:8080/api/v1/taxas/SELIC

# IPCA — atendido por BrasilAPI ou BCB SGS (HG Brasil não cobre)
curl http://localhost:8080/api/v1/taxas/ipca
```

```json
{
  "nome": "CDI",
  "valor": 10.65,
  "dataReferencia": "2026-05-05"
}
```

### Endpoints expostos

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/v1/cep/{cep}` | Resolução de CEP com fallback em cascata. |
| `GET` | `/api/v1/cep/busca` | Busca de CEPs por endereço textual. Params: `uf`, `cidade`, `logradouro` (todos obrigatórios). |
| `GET` | `/api/v1/cep/reverso` | Geocodificação reversa: coordenadas → CEP. Params: `lat`, `lon` (obrigatórios, WGS84). |
| `GET` | `/api/v1/cnpj/{cnpj}` | Resolução de CNPJ com fallback em cascata. |
| `GET` | `/api/v1/calendario/feriados/{ano}` | Lista de feriados (use `?siglaUf=SP` para incluir estaduais). |
| `GET` | `/api/v1/calendario/proximo-dia-util` | Próximo dia útil (params: `data=yyyy-MM-dd`, `siglaUf` opcional). |
| `GET` | `/api/v1/taxas/{sigla}` | Cotação de índice financeiro (`cdi`, `selic` ou `ipca`, case-insensitive). |
| `GET` | `/api/v1/rastreio/{codigo}` | Histórico de eventos de uma encomenda dos Correios (padrão `LB123456789BR`). |
| `GET` | `/api/v1/bancos` | Catálogo completo de instituições financeiras (ISPB + COMPE). |
| `GET` | `/api/v1/bancos/{codigo}` | Instituição por código COMPE (1 a 3 dígitos, zero-pad opcional). |
| `GET` | `/api/v1/fipe/preco/{codigoFipe}/{anoModelo}` | Cotação FIPE de veículo (padrão `000000-0` + ano `yyyy` ou `32000` para Zero KM). |
| `GET` | `/api/v1/placa/{placa}` | Identificação de veículo por placa (padrão antigo `ABC1234` ou Mercosul `ABC1D23`); chassi mascarado. |
| `GET` | `/api/v1/avaliacao/placa/{placa}` | Avaliação cruzada placa + FIPE + mercado real (scraping em tempo real OLX/MobiAuto). Aceita `?codigoFipe=000000-0` opcional. |
| `GET` | `/api/v1/avaliacao/manual` | Avaliação manual livre de tokens de placa — direto FIPE + mercado. Params: `marca`, `modelo`, `ano` (obrigatórios), `codigoFipe` (opcional). |
| `GET` | `/api/v1/saude/fns/{ibge}` | Repasses do Fundo Nacional de Saúde para o município numa competência. Params: `competencia=yyyy-MM` (obrigatório). |
| `GET` | `/api/v1/saude/egestor/{ibge}` | Equipes APS detalhadas (INE, tipo, custeio, status de suspensão) reportadas pelo e-Gestor. Params: `competencia=yyyy-MM` (obrigatório). |
| `GET` | `/api/v1/saude/cnes/{cnesBase}/profissionais` | Profissionais cadastrados num estabelecimento CNES (todas as equipes). Params: `ibge` (obrigatório — chave composta upstream). |
| `GET` | `/api/v1/saude/sisab/{ibge}/producao` | Validação SISAB (Aprovado/Reprovado) por equipe (CNES+INE) numa competência. Params: `competencia=yyyy-MM` (obrigatório). |
| `GET` | `/api/v1/saude/auditoria/inadimplencia` | **Rota mágica** — cruza e-Gestor + CNES + SISAB e aponta os profissionais sem produção que causaram a suspensão de repasse. Params: `ibge`, `cnes`, `competencia=yyyy-MM` (todos obrigatórios). |
| `GET` | `/swagger-ui.html` | Documentação interativa da API. |
| `GET` | `/v3/api-docs` | Schema OpenAPI 3 (JSON). |
| `GET` | `/actuator/health` | Liveness/Readiness probes. |
| `GET` | `/actuator/prometheus` | Métricas em formato Prometheus. |

### Configuração via variáveis de ambiente

| Variável | Default | O que faz |
|---|---|---|
| `SPRING_DATA_REDIS_HOST` | `localhost` | Host do Redis. |
| `SPRING_DATA_REDIS_PORT` | `6379` | Porta do Redis. |
| `GATEWAY_RATE_LIMIT_ENABLED` | `false` | **Ative apenas no Playground público** (5 req/min/IP). Em deploy corporativo, mantenha desativado para throughput ilimitado. |
| `GATEWAY_FLARESOLVERR_URL` | *(vazio)* | URL do sidecar FlareSolverr (ex: `http://flaresolverr:8191`). **Obrigatório para `/api/v1/saude/cnes/*` e `/api/v1/saude/egestor/*`**, **para `/api/v1/fipe/*`** (provedor primário desde 2026-05, ver seção FIPE acima), e habilita o fallback de Web Scraping de placas (`placafipe.com`). Sem esta var, essas rotas devolvem 503 com mensagem clara; o resto do gateway funciona normalmente. Veja a seção **Deploy Produtivo & Bypass Anti-Bot** abaixo. |
| `GATEWAY_SISAB_SIDECAR_URL` | *(vazio)* | URL do sidecar SISAB (ex: `http://sisab-sidecar:8000`). **Obrigatório exclusivamente para `/api/v1/saude/sisab/*`** (validação de produção). FlareSolverr não cobre essa rota porque a página JSF/Mojarra do SISAB usa plugins Bootstrap Multiselect cujo estado só sincroniza via cliques reais no DOM — Selenium dedicado é a única abordagem confiável. Sem esta var, SISAB devolve 503 com mensagem clara. Container provido em `services/sisab-sidecar/` no compose. |
| `SERVER_PORT` | `8080` | Porta HTTP. |

---

## 🚀 Deploy Produtivo & Bypass Anti-Bot

Em produção, parte das rotas do gateway atravessa **WAFs governamentais** (F5 BIG-IP em `cnes.datasus.gov.br`, `relatorioaps-prd.saude.gov.br`, `sisab.saude.gov.br`) e **Cloudflare** (`placafipe.com`). Esses fronts rejeitam consistentemente requisições originadas de processos Java — mesmo com handshakes meticulosos de cookies, headers `Sec-Fetch-*` e ViewState JSF preservado. Validamos todas as alternativas; nenhuma combinação puramente Java passa.

A solução adotada é o **padrão "Sidecar Cirúrgico"** com [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) — um pequeno serviço auxiliar que dirige um Chromium headless e devolve o body resolvido após o desafio. **Não embarcamos Selenium no container Java**: o gateway continua leve (~250 MB), e o peso de um browser real (~600 MB) fica isolado num container separado, ativado apenas onde necessário.

### Arquitetura híbrida (FlareSolverr é opcional)

```
                        ┌──────────────────────────────────────┐
        cliente ────────▶          api (Java/Spring)           │
                        │                                      │
                        │  /api/v1/cep/*       ─────────────┐  │
                        │  /api/v1/cnpj/*                   │  │
                        │  /api/v1/calendario/*             │  │
                        │  /api/v1/taxas/*                  │  │
                        │  /api/v1/rastreio/*               ├──┼──▶ APIs públicas
                        │  /api/v1/bancos/*                 │  │    (BrasilAPI, ViaCEP, …)
                        │  /api/v1/fipe/*                   │  │
                        │  /api/v1/saude/fns/*              │  │
                        │  /api/v1/avaliacao/*  ─────────── ┘  │
                        │  /api/v1/placa/*       (cascata pode │
                        │                         cair no ↓)   │
                        │                                      │
                        │  /api/v1/saude/cnes/*    ─┐          │
                        │  /api/v1/saude/egestor/* ─┤          │
                        │  /api/v1/saude/sisab/*   ─┤          │
                        │  PlacaFipe (fallback)    ─┴───┐      │
                        └─────────────────────────┬─────┴──────┘
                                                  │
                                                  ▼
                                         ┌──────────────────┐
                                         │   flaresolverr   │
                                         │ (Chromium headless)│
                                         └────────┬─────────┘
                                                  ▼
                                          gov.br WAF / Cloudflare
```

**Sem nenhum sidecar** (deploy "leve"):
- Funcionam normalmente: CEP, CNPJ, calendário, taxas, rastreio, bancos, FNS, avaliação OLX/MobiAuto, placa (WDApi/Keplaca quando há tokens).
- Devolvem **503** com mensagem clara: rotas CNES, e-Gestor, FIPE, SISAB e o fallback PlacaFipe da cascata de placas. A mensagem é literal e específica para cada rota — operadores entendem o caminho imediatamente.
- **Importante (mudança 2026-05)**: a rota FIPE migrou para depender do FlareSolverr porque BrasilAPI e Parallelum quebraram simultaneamente upstream. Os dois legados continuam na cascata como fallback automático para o dia em que recuperarem.

**Dois sidecares — papéis distintos**:
- **FlareSolverr** (`http://flaresolverr:8191`) cobre WAFs anti-bot (Cloudflare em `placafipe.com`, F5 BIG-IP em `cnes.datasus.gov.br`, `relatorioaps-prd.saude.gov.br`, `veiculos.fipe.org.br`). Usado para 4 domínios: FIPE-Oficial, CNES, e-Gestor e fallback PlacaFipe.
- **SISAB Sidecar** (`http://sisab-sidecar:8000`, Python + Selenium) cobre **exclusivamente** a rota SISAB Validação. FlareSolverr não funciona para essa rota porque exige interação JS real com plugins Bootstrap Multiselect — `executeJS` não é exposto pelo FlareSolverr v3.

**Com ambos os sidecares** (deploy "completo"):
- Todas as rotas funcionam, incluindo as 3 do auditor APS (CNES, e-Gestor, SISAB), a rota mágica `/api/v1/saude/auditoria/inadimplencia` e a tabela FIPE direto da fundação oficial.
- A cascata de placas cai limpamente no PlacaFipe scraper sem 403 do Cloudflare.
- Latência adicional: ~5-10s na primeira chamada FIPE/Saúde via FlareSolverr, ~14s na primeira chamada SISAB via Selenium; cache Redis de 15-365 dias absorve as seguintes para ~40 ms.

### Como ativar

No `docker-compose.yml` da raiz, descomente as três marcações em conjunto:

1. A variável `GATEWAY_FLARESOLVERR_URL: http://flaresolverr:8191` no serviço `api`.
2. A dependência `flaresolverr: condition: service_started` no `depends_on` do `api`.
3. O bloco completo do serviço `flaresolverr` (imagem `ghcr.io/flaresolverr/flaresolverr:latest`, porta `8191:8191`).

Suba normalmente:

```bash
docker compose up -d --build
```

Pronto — as rotas Saúde respondem 200 ao invés de 503, e o gateway resolve `placafipe.com` sem cair em Cloudflare.

### Por que opcional?

Nem todo deploy precisa das rotas Saúde. Empresas que consomem só CEP+CNPJ+FIPE não querem rodar Chromium em produção. O design respeita isso: o sidecar é uma **escolha consciente do operador**, não um custo embutido.

### Anatomia do invoker

Toda chamada para o FlareSolverr passa por uma classe única — `config/FlareSolverrInvoker.java` — que faz `POST /v1` com `{"cmd": "request.get|post", "url": "...", "cookies": [...], "postData": "..."}` e extrai `solution.response`. Os 4 clients que usam (`PlacaFipeScraperClient`, `CnesWebClient`, `EGestorWebClient`, `SisabWebClient`) compartilham essa única implementação — zero duplicação de envelope JSON, single point of evolution se a API do FlareSolverr mudar.

---

## Observabilidade

Configure o Prometheus para fazer *scrape* de `/actuator/prometheus` e use estas queries para construir seu dashboard:

```promql
# Taxa de sucesso por provedor nos últimos 5 minutos, por domínio
sum(rate(gateway_provider_requests_total{outcome="success"}[5m])) by (domain, provider)

# P99 de latência por upstream
histogram_quantile(0.99,
  sum(rate(gateway_provider_latency_seconds_bucket[5m])) by (le, provider)
)

# Estado dos Circuit Breakers (alerte quando > 0)
resilience4j_circuitbreaker_state{state="open"}

# Sinal de degradação: calculador offline servindo feriados
# (significa que ambos os providers HTTP estão fora)
rate(gateway_provider_requests_total{provider="in-memory-calculator"}[5m]) > 0
```

---

## Como Contribuir

A comunidade brasileira de software vem resolvendo essa dor há mais de uma década com gambiarras isoladas. **Vamos resolver juntos, em código aberto.**

1. **Issues abertas** descrevem features candidatas e bugs conhecidos. Comece por uma marcada com `good-first-issue` se quiser entrar leve.
2. **Pull Requests** são bem-vindos. Antes de abrir, rode os testes de integração:
   ```bash
   ./mvnw verify
   ```
   (sobe um Redis Testcontainer, então é preciso ter Docker disponível).
3. **Novos provedores** são especialmente apreciados — basta implementar `CepClientProvider`, `CnpjClientProvider` ou `FeriadoClientProvider`, registrar uma instância de Circuit Breaker no `application.yml`, e adicionar à lista do service correspondente.
4. **Padrão de commit** segue o [Conventional Commits](https://www.conventionalcommits.org/pt-br/v1.0.0/) (`feat:`, `fix:`, `refactor:` etc.).
5. **Discussões arquiteturais** acontecem nas issues marcadas com `rfc`. Trade-offs são bem-vindos; pull requests sem contexto de motivação tendem a esfriar.

Se este projeto te poupou uma madrugada de plantão, deixe uma estrela. Se te poupou uma semana de retrabalho, abra uma issue contando — queremos saber.

---

## Licença

Distribuído sob a [**Apache License 2.0**](LICENSE). Use em produção, *fork* à vontade, redistribua com ou sem modificações — desde que mantenha o aviso de copyright e a cópia da licença. A Apache 2.0 inclui ainda **concessão expressa de patentes**, oferecendo segurança jurídica adicional para adoção corporativa.
