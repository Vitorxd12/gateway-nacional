# CerneBR Gateway Nacional

> **A infraestrutura definitiva e self-hosted para consumo de APIs brasileiras.**
> Latência mínima, resiliência industrial e zero acoplamento com a instabilidade da internet pública.

[![Build](https://img.shields.io/badge/build-passing-brightgreen)](#)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange)](#)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F)](#)
[![Docker Ready](https://img.shields.io/badge/docker-ready-2496ED)](#)

---

## O Problema

Toda empresa brasileira que constrói software já passou por isso:

- **Rate limits agressivos** — A primeira release sobe bem; na quinta-feira do mês seguinte, o ReceitaWS começa a devolver `HTTP 429` em massa e a sua tela de cadastro de fornecedor para de funcionar.
- **Quedas em cascata** — O ViaCEP cai num feriado, o seu checkout cai junto, e o seu time de plantão é acordado às 3h da manhã para descobrir que **o problema não é seu**.
- **Rateio de IP em ambientes cloud** — Em Kubernetes, todos os seus pods saem pelo mesmo egress NAT. Para a API pública lá fora, vocês são *um único cliente* — e batem o limite gratuito antes do almoço.
- **Formatos divergentes** — ViaCEP devolve `localidade`, BrasilAPI devolve `city`. ReceitaWS retorna `nome`, MinhaReceita retorna `razao_social`. Cada provedor é uma migração de schema disfarçada.
- **Código IBGE faltando** — Sua emissão de NF-e exige o código IBGE do município, mas metade dos provedores não devolve esse campo. Você precisa de uma tabela auxiliar e ninguém quer manter.

## A Solução

O **Gateway Nacional** é um microserviço self-hosted que **blinda a sua aplicação** dessas patologias. Você consulta um único endpoint local, e toda a complexidade desaparece:

- **Cascata de resiliência inteligente** — `ViaCEP → BrasilAPI → AwesomeAPI` para CEP, `BrasilAPI → ReceitaWS → MinhaReceita` para CNPJ. Se o primário cai, o secundário responde *antes do seu cliente perceber*.
- **Circuit Breakers isolados por upstream** — A queda do ViaCEP não contamina a percepção de saúde do BrasilAPI. Cada provedor tem seu próprio sliding window.
- **Cache distribuído agressivo** — Redis com TTL de 30 dias para CEP (CEPs não mudam) e 24h para CNPJ. **Após o primeiro hit, sua latência cai para sub-milissegundos.**
- **Enriquecimento in-memory de código IBGE** — Quando o provedor responde sem o IBGE, o gateway preenche localmente a partir de um índice in-memory normalizado (NFD + lowercase). Lookup O(1), zero round-trip.
- **Schema unificado** — Você lida com **um único DTO** (`CepResponse`, `CnpjResponse`). A divergência de formato dos upstreams fica isolada na camada de Anti-Corruption.
- **Rate limit como feature toggle** — A versão hospedada (Playground) limita 5 req/min/IP para conter abuso; em **produção self-hosted, basta desligar a flag** e ter throughput ilimitado.

---

## Arquitetura Enterprise

Cada decisão técnica foi tomada com um propósito específico. Sem cargo cult.

| Tecnologia | Por que está aqui |
|---|---|
| **Java 25 + Virtual Threads** | Cada request HTTP roda em uma virtual thread (JEP 444). Para uma carga *I/O-bound* como a nossa — esperando respostas de APIs externas — isso significa **dezenas de milhares de requisições concorrentes** com pegada de memória mínima, sem o ônus de pools de threads tradicionais. O `JdkClientHttpRequestFactory` outbound também usa `Executors.newVirtualThreadPerTaskExecutor()`, garantindo virtual threads ponta-a-ponta. |
| **Resilience4j** | Circuit Breaker e TimeLimiter declarativos via `@CircuitBreaker(name = "viaCepCB")`. **Uma instância nomeada por upstream** — falha do ViaCEP nunca abre o CB do BrasilAPI. Sliding window de 10 chamadas, 50% failure rate, 15s no estado *open*. |
| **Bucket4j + Redis** | Rate limiter distribuído via `LettuceBasedProxyManager`. O bucket vive no Redis, então você pode escalar horizontalmente os pods do gateway sem que cada réplica tenha sua própria contagem. **Toggle via `gateway.rate-limit.enabled`** — `true` no Playground público, `false` no deploy corporativo. |
| **Anti-Corruption Layer** | Cada client externo (`ViaCepClient`, `ReceitaWsClient`, etc.) tem um **Record Java privado** que mapeia o JSON específico daquele provedor e converte para o DTO unificado do gateway. Quando o ReceitaWS muda `nome` para `razao_social` na próxima versão, **só uma classe muda** — o resto da aplicação não percebe. |
| **Micrometer + Prometheus** | Duas métricas estratégicas: `gateway.provider.requests` (counter, com tags `domain`/`provider`/`outcome`) e `gateway.provider.latency` (timer). Prontas para scrape em `/actuator/prometheus` — você liga o Grafana e tem dashboards de saúde dos upstreams em minutos. |
| **Spring Boot 4 + ProblemDetail** | Tratamento global de exceções segue **RFC 7807** (`ProblemDetail`). Toda falha — validação, upstream indisponível, rate limit — devolve o **mesmo formato JSON** com `type`, `title`, `detail`, `instance` e `traceId` para correlação com logs. Stacktraces *jamais* vazam ao cliente. |
| **Cache (Redis) com TTL por domínio** | `RedisCacheManager` configurado com TTL padrão de 24h e *override* específico de 30 dias para o cache `ceps`. Diferentes domínios têm diferentes velocidades de mudança — a config respeita isso. |
| **Docker multi-stage + healthcheck** | Build em duas etapas (Maven + JDK 25 → JRE 25 Alpine). Imagem final pequena, usuário não-root, `MaxRAMPercentage=75` para respeitar limites de cgroup. `docker-compose.yml` com `depends_on: condition: service_healthy` para garantir que o Redis aceita conexões antes do app subir. |

### Topologia em 30 segundos

```
            ┌─────────────────────────────────────────────────────────┐
            │                  Gateway Nacional                        │
Cliente ───▶│  ┌──────────────┐    ┌─────────────────┐                 │
   HTTP     │  │ Rate Limiter │───▶│  Cache (Redis)  │── hit ──▶ resp  │
            │  │  (Bucket4j)  │    └────────┬────────┘                 │
            │  └──────────────┘             │ miss                     │
            │                               ▼                          │
            │  ┌─────────────────────────────────────────┐             │
            │  │        Service Orquestrador             │             │
            │  │  ┌───────┐  fail  ┌───────┐  fail  ┌─┐  │             │
            │  │  │ CB #1 │ ─────▶ │ CB #2 │ ─────▶ │ │  │             │
            │  │  └───┬───┘        └───┬───┘        └─┘  │             │
            │  └──────┼────────────────┼─────────────────┘             │
            └─────────┼────────────────┼───────────────────────────────┘
                     ▼                ▼
                 ViaCEP          BrasilAPI         (...AwesomeAPI)
```

---

## Quick Start

### Modo Desenvolvimento (Maven)

Pré-requisitos: **JDK 25** e um Redis local rodando em `localhost:6379`.

```bash
# Sobe um Redis efêmero para desenvolvimento
docker run -d --name dev-redis -p 6379:6379 redis:7-alpine

# Roda a aplicação
./mvnw spring-boot:run
```

A aplicação fica disponível em `http://localhost:8080`. O Swagger UI em `/swagger-ui.html`.

Para rodar a suíte de testes de integração (Testcontainers + WireMock):

```bash
./mvnw verify
```

### Modo Produção (Docker Compose)

Um comando, e a stack inteira sobe — aplicação + Redis com healthcheck + dependência ordenada:

```bash
docker compose up -d --build
```

Para usar **sem rate limiting** (cenário corporativo, ilimitado), sobrescreva a env do serviço `api` no `docker-compose.yml`:

```yaml
environment:
  GATEWAY_RATE_LIMIT_ENABLED: "false"
  SPRING_DATA_REDIS_HOST: redis
```

### Consumindo a API

```bash
# Consulta de CEP
curl http://localhost:8080/api/v1/cep/01001000

# Consulta de CNPJ (Banco do Brasil — público, ótimo para teste)
curl http://localhost:8080/api/v1/cnpj/00000000000191

# Health check
curl http://localhost:8080/actuator/health

# Métricas Prometheus
curl http://localhost:8080/actuator/prometheus | grep gateway_provider
```

Resposta unificada de CEP:

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

### Endpoints expostos

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/v1/cep/{cep}` | Resolução de CEP com fallback em cascata. |
| `GET` | `/api/v1/cnpj/{cnpj}` | Resolução de CNPJ com fallback em cascata. |
| `GET` | `/swagger-ui.html` | Documentação interativa da API. |
| `GET` | `/v3/api-docs` | Schema OpenAPI 3 (JSON). |
| `GET` | `/actuator/health` | Liveness/Readiness probes. |
| `GET` | `/actuator/prometheus` | Métricas em formato Prometheus. |

---

## Configuração

Todas as propriedades importantes podem ser sobrescritas via variáveis de ambiente:

| Variável | Default | O que faz |
|---|---|---|
| `SPRING_DATA_REDIS_HOST` | `localhost` | Host do Redis. |
| `SPRING_DATA_REDIS_PORT` | `6379` | Porta do Redis. |
| `GATEWAY_RATE_LIMIT_ENABLED` | `true` | Liga/desliga o rate limiter de 5 req/min/IP. |
| `GATEWAY_CEP_VIACEP_BASE_URL` | `https://viacep.com.br` | Override de URL (útil para staging/testes). |
| `SERVER_PORT` | `8080` | Porta HTTP. |

---

## Observabilidade

Configure o Prometheus para fazer *scrape* de `/actuator/prometheus` e use estas queries para construir seu dashboard:

```promql
# Taxa de sucesso por provedor de CEP nos últimos 5 minutos
sum(rate(gateway_provider_requests_total{domain="cep",outcome="success"}[5m])) by (provider)

# P99 de latência por upstream
histogram_quantile(0.99,
  sum(rate(gateway_provider_latency_seconds_bucket[5m])) by (le, provider)
)

# Estado dos Circuit Breakers
resilience4j_circuitbreaker_state{state="open"}
```

---

## Como Contribuir

A comunidade brasileira de software vem resolvendo essa dor há mais de uma década com gambiarras isoladas. **Vamos resolver juntos, em código aberto.**

1. **Issues abertas** descrevem features candidatas e bugs conhecidos. Comece por uma marcada com `good-first-issue` se você quiser entrar leve.
2. **Pull Requests** são bem-vindos. Antes de abrir, rode os testes de integração (`./mvnw verify` — sobe um Redis Testcontainer, então é preciso ter Docker disponível).
3. **Novos provedores** são especialmente apreciados — basta implementar `CepClientProvider` ou `CnpjClientProvider`, registrar uma instância de Circuit Breaker no `application.yml`, e adicionar à lista do service correspondente.
4. **Padrão de commit** segue o [Conventional Commits](https://www.conventionalcommits.org/pt-br/v1.0.0/) (`feat:`, `fix:`, `refactor:` etc.).
5. **Discussões arquiteturais** acontecem nas issues marcadas com `rfc`. Trade-offs são bem-vindos; pull requests sem contexto de motivação tendem a esfriar.

Se este projeto te poupou uma madrugada de plantão, deixe uma estrela. Se te poupou uma semana de retrabalho, abra uma issue contando — queremos saber.

---

## Licença

APACHE. Use em produção, fork à vontade, contribua de volta quando puder. Veja [LICENSE](LICENSE).
