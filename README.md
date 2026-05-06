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
# Resolução básica
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
| `GET` | `/api/v1/cnpj/{cnpj}` | Resolução de CNPJ com fallback em cascata. |
| `GET` | `/api/v1/calendario/feriados/{ano}` | Lista de feriados (use `?siglaUf=SP` para incluir estaduais). |
| `GET` | `/api/v1/calendario/proximo-dia-util` | Próximo dia útil (params: `data=yyyy-MM-dd`, `siglaUf` opcional). |
| `GET` | `/api/v1/taxas/{sigla}` | Cotação de índice financeiro (`cdi`, `selic` ou `ipca`, case-insensitive). |
| `GET` | `/api/v1/rastreio/{codigo}` | Histórico de eventos de uma encomenda dos Correios (padrão `LB123456789BR`). |
| `GET` | `/api/v1/bancos` | Catálogo completo de instituições financeiras (ISPB + COMPE). |
| `GET` | `/api/v1/bancos/{codigo}` | Instituição por código COMPE (1 a 3 dígitos, zero-pad opcional). |
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
| `SERVER_PORT` | `8080` | Porta HTTP. |

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
