# CerneBR -- Gateway de APIs Nacionais de Alta Performance

**Agregador self-hosted de APIs publicas brasileiras. Cache local via Redis, resiliencia com Circuit Breakers e observabilidade nativa para ambientes corporativos.**

---

## Sumario

- [O Problema](#o-problema)
- [A Solucao](#a-solucao)
- [Por que Self-Hosted?](#por-que-self-hosted)
- [Stack Tecnologica](#stack-tecnologica)
- [Funcionalidades da Versao 1.0](#funcionalidades-da-versao-10)
- [Como Executar](#como-executar)
- [Documentacao da API](#documentacao-da-api)
- [Observabilidade](#observabilidade)
- [Como Contribuir](#como-contribuir)
- [Licenca](#licenca)

---

## O Problema

Sistemas corporativos brasileiros -- ERPs, CRMs, plataformas de e-commerce, fintechs e healthtechs -- precisam consultar dados publicos diariamente: CNPJ na Receita Federal, enderecos via CEP, codigos IBGE para emissao de NF-e, feriados nacionais para calculo de vencimentos.

Na pratica, isso significa depender de APIs governamentais e gratuitas que apresentam:

- **Instabilidade cronica.** A Receita Federal, ViaCEP e servicos dos Correios ficam fora do ar com frequencia, sem SLA definido e sem aviso previo.
- **Rate limits agressivos.** Provedores bloqueiam IPs apos poucas dezenas de requisicoes consecutivas, interrompendo processos de importacao em lote.
- **Latencia imprevisivel.** Tempos de resposta variam de 200ms a mais de 10 segundos, degradando a experiencia do usuario final e comprometendo pipelines de dados.
- **Risco regulatorio.** Cada chamada externa trafega dados de CNPJ e enderecos por servidores de terceiros, criando vetores de exposicao desnecessarios sob a otica da LGPD.

O resultado e previsivel: sistemas travando em horario comercial, filas de processamento represadas, cadastros incompletos e equipes de infraestrutura apagando incendios que nao sao delas.

---

## A Solucao

O **CerneBR** e um gateway unificado que agrega multiplas APIs publicas brasileiras em uma unica interface REST, projetado para rodar dentro da infraestrutura da propria empresa.

A arquitetura e simples e deliberada:

```
                          +-------------------+
  Sua Aplicacao  ------>  |    CerneBR        |
  (ERP, CRM, SaaS)       |  (sua VPC/rede)   |
                          |                   |
                          |  +-------------+  |     +----------------+
                          |  |  Redis      |  |     | Receita Federal|
                          |  |  (cache)    |  |     | ViaCEP         |
                          |  +-------------+  |---->| BrasilAPI      |
                          |                   |     | AwesomeAPI     |
                          |  Circuit Breaker  |     | Correios       |
                          |  + Fallback       |     +----------------+
                          +-------------------+
```

1. Sua aplicacao consulta o CerneBR via rede local (latencia de microsegundos).
2. O CerneBR verifica o cache Redis. Se o dado ja existe e esta dentro do TTL, retorna imediatamente sem nenhuma chamada externa.
3. Em caso de cache miss, o gateway consulta o provedor primario. Se ele falhar ou ultrapassar o timeout, o Circuit Breaker redireciona automaticamente para o proximo provedor da cadeia de fallback.
4. A resposta bem-sucedida e normalizada em um formato unificado, cacheada no Redis e devolvida ao chamador.

O efeito pratico: apos o warm-up inicial, mais de 95% das consultas sao resolvidas em menos de 1ms direto do cache, sem qualquer dependencia externa.

---

## Por que Self-Hosted?

O modelo self-hosted nao e uma limitacao -- e uma decisao de arquitetura com implicacoes diretas em seguranca, performance e conformidade.

### Seguranca e LGPD

Ao rodar o CerneBR dentro da sua VPC, os dados consultados (CNPJs, enderecos, razoes sociais) nunca trafegam por servidores de terceiros. O cache fica na sua infraestrutura, sob as suas politicas de retencao e acesso. Isso elimina um vetor de exposicao relevante e simplifica auditorias de conformidade com a Lei Geral de Protecao de Dados.

### Performance Previsivel

A latencia entre sua aplicacao e o CerneBR e determinada pela sua propria rede, nao pela internet publica. Em ambientes Kubernetes ou Docker Compose, isso significa respostas sub-milissegundo para consultas cacheadas. Nao ha variabilidade de CDN, roteamento BGP ou congestionamento de provedores intermediarios.

### Zero Bloqueio de IP

APIs publicas aplicam rate limits por IP de origem. Quando multiplos clientes compartilham um gateway SaaS, o IP do gateway e bloqueado por conta do trafego agregado de todos os clientes. Com o modelo self-hosted, as requisicoes partem do IP da sua infraestrutura, que consulta apenas os seus proprios volumes -- eliminando o problema.

### Resiliencia Sob Seu Controle

O cache Redis e os Circuit Breakers funcionam como uma camada de isolamento: mesmo que todas as APIs externas caiam simultaneamente, sua aplicacao continua recebendo dados do cache local ate que elas se recuperem. Nao ha SLA de terceiros do qual voce dependa.

---

## Stack Tecnologica

Cada componente da stack foi escolhido com base em criterios concretos de performance, maturidade e adequacao ao problema.

| Componente | Versao | Justificativa |
|---|---|---|
| **Java** | 25 | Virtual Threads (Project Loom) permitem que cada requisicao rode em uma thread virtual extremamente leve. Isso elimina a necessidade de pools de threads dimensionados manualmente e permite que milhares de chamadas I/O-bound concorrentes (consultas a APIs externas) coexistam sem overhead significativo de memoria ou context switching. |
| **Spring Boot** | 4.0 | Framework de referencia no ecossistema Java corporativo. Oferece autoconfiguracoes maduras para Redis, Actuator, Resilience4j e OpenAPI, reduzindo boilerplate e acelerando o time-to-production. |
| **Spring Data Redis** | -- | Abstrai a comunicacao com o Redis via Lettuce (driver non-blocking). O pool de conexoes e configuravel, e a anotacao `@Cacheable` permite cache declarativo em qualquer metodo de servico sem acoplamento ao driver. |
| **Resilience4j** | -- | Implementacao leve e modular de padroes de resiliencia (Circuit Breaker, Time Limiter, Retry). Cada provedor externo tem seu proprio Circuit Breaker com janela deslizante configuravel, evitando que a falha de um provedor propague latencia para toda a aplicacao. |
| **Docker** | -- | Distribuicao oficial via imagens prontas. Um unico `docker compose up` sobe o gateway e o Redis em qualquer maquina com Docker instalado, sem dependencias de JDK ou Maven no host. |
| **Spring Boot Actuator + Micrometer Prometheus** | -- | Exposicao nativa de metricas (latencia por endpoint, estado dos Circuit Breakers, taxa de cache hit/miss) no formato Prometheus. Pronto para ser consumido pelo Grafana ja existente na infraestrutura da empresa. |
| **SpringDoc OpenAPI** | 3.0 | Documentacao interativa da API gerada automaticamente a partir das anotacoes do codigo. Swagger UI acessivel em `/swagger-ui.html` para testes manuais e integracao com ferramentas de contrato. |

---

## Funcionalidades da Versao 1.0

### 1. Consulta de CEP -- Fallback em Cascata com Cache

```
GET /api/v1/cep/{cep}
```

Resolve enderecos brasileiros a partir do CEP de 8 digitos. O CerneBR consulta tres provedores em cadeia (ViaCEP, BrasilAPI e AwesomeAPI), cada um protegido por seu proprio Circuit Breaker. Se o provedor primario falhar ou ultrapassar o timeout de 3 segundos, a requisicao e automaticamente redirecionada para o proximo.

O resultado e normalizado em um payload unificado e cacheado no Redis. Consultas subsequentes para o mesmo CEP sao resolvidas diretamente do cache, sem chamada externa.

**Exemplo de resposta:**

```json
{
  "cep": "01001-000",
  "logradouro": "Praca da Se",
  "complemento": "lado impar",
  "bairro": "Se",
  "localidade": "Sao Paulo",
  "uf": "SP",
  "ibge": "3550308"
}
```

**Comportamento de resiliencia:**

- Timeout por provedor: 3 segundos (configuravel).
- Circuit Breaker: janela de 10 chamadas, threshold de 50% de falha para abertura.
- Recuperacao automatica: apos 15 segundos em estado OPEN, o CB transiciona para HALF_OPEN e testa 3 chamadas antes de fechar novamente.

---

### 2. Consulta de CNPJ -- Cache Longo na Receita Federal

```
GET /api/v1/cnpj/{cnpj}
```

Busca dados cadastrais de empresas (razao social, nome fantasia, situacao cadastral, endereco, atividades economicas) diretamente de fontes ligadas a Receita Federal.

Dados de CNPJ mudam com pouca frequencia. O CerneBR aplica um TTL de cache entre 7 e 15 dias (configuravel), o que significa que apos a primeira consulta, centenas de requisicoes subsequentes sao resolvidas localmente em microsegundos, sem nenhuma chamada externa. Isso e especialmente relevante para operacoes de importacao em lote, onde o mesmo CNPJ e consultado repetidamente.

> **Status:** planejado para a release 1.0.

---

### 3. Codigos IBGE -- Municipios e Unidades Federativas

```
GET /api/v1/ibge/municipios/{uf}
GET /api/v1/ibge/estados
```

Fornece a tabela oficial de codigos IBGE para municipios e UFs. Esses codigos sao obrigatorios nos campos `cMunFG`, `cMunCarga` e `cUF` do XML da NF-e e da NFS-e. Erros nesses campos causam rejeicao do documento fiscal na SEFAZ.

Por se tratar de dados altamente estaticos (atualizados pelo IBGE uma vez por ano, no maximo), o CerneBR carrega essas tabelas diretamente na memoria da aplicacao no momento do boot. A latencia de consulta e efetivamente zero -- nao ha chamada a Redis, muito menos a APIs externas.

> **Status:** planejado para a release 1.0.

---

### 4. Feriados e Dias Uteis

```
GET /api/v1/feriados/{ano}
GET /api/v1/feriados/dias-uteis?de=2026-01-01&ate=2026-01-31
```

Endpoint para consulta de feriados nacionais e calculo de dias uteis entre duas datas. Essencial para sistemas financeiros que precisam calcular vencimentos de boletos, prazos de liquidacao e janelas de compensacao pulando feriados e fins de semana.

Os feriados fixos sao embutidos no codigo. Feriados moveis (Carnaval, Sexta-Feira Santa, Corpus Christi) sao calculados algoritmicamente a partir da Pascoa.

> **Status:** planejado para a release 1.0.

---

## Como Executar

### Pre-requisitos

- Docker e Docker Compose instalados.

### Subindo com Docker Compose

Crie um arquivo `docker-compose.yml` na raiz do seu projeto ou diretorio de deploy:

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    command: redis-server --save 60 1 --loglevel warning
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5

  gateway:
    image: cernebr/gateway-nacional:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_DATA_REDIS_HOST: redis
    depends_on:
      redis:
        condition: service_healthy

volumes:
  redis_data:
```

```bash
docker compose up -d
```

Apos alguns segundos, o gateway estara acessivel:

```bash
# Health check
curl http://localhost:8080/actuator/health

# Consultar um CEP
curl http://localhost:8080/api/v1/cep/01001000

# Acessar a documentacao interativa
# Abra no navegador: http://localhost:8080/swagger-ui.html
```

### Desenvolvimento Local (sem Docker)

Para contribuidores que preferem rodar o projeto diretamente:

```bash
# Pre-requisitos: JDK 25+, Redis rodando em localhost:6379

# Clonar o repositorio
git clone https://github.com/Jovinull/gateway-nacional.git

# Compilar e executar
./mvnw spring-boot:run
```

A aplicacao estara disponivel em `http://localhost:8080`.

---

## Documentacao da API

O CerneBR gera documentacao OpenAPI 3.0 automaticamente a partir das anotacoes do codigo-fonte. Dois endpoints estao disponiveis:

| Recurso | URL |
|---|---|
| Swagger UI (interativo) | `http://localhost:8080/swagger-ui.html` |
| Especificacao OpenAPI (JSON) | `http://localhost:8080/v3/api-docs` |

A interface Swagger UI permite testar todos os endpoints diretamente no navegador, com exemplos de request e response pre-preenchidos.

---

## Observabilidade

O CerneBR expoe metricas e health checks nativamente, sem necessidade de agentes ou sidecars adicionais.

### Endpoints do Actuator

| Endpoint | Descricao |
|---|---|
| `/actuator/health` | Liveness e readiness probes. Inclui estado dos Circuit Breakers e conectividade com o Redis. |
| `/actuator/info` | Informacoes da aplicacao (versao, build). |
| `/actuator/metrics` | Metricas em formato Micrometer (latencia, throughput, uso de memoria). |
| `/actuator/prometheus` | Metricas no formato Prometheus, prontas para scrape. |

### Integracao com Prometheus e Grafana

Adicione o seguinte job ao seu `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'cernebr-gateway'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['gateway:8080']
```

Metricas relevantes disponiveis:

- `http_server_requests_seconds` -- latencia por endpoint, metodo e status HTTP.
- `resilience4j_circuitbreaker_state` -- estado atual de cada Circuit Breaker (CLOSED, OPEN, HALF_OPEN).
- `resilience4j_circuitbreaker_failure_rate` -- taxa de falha por provedor.
- `cache_gets_total` e `cache_puts_total` -- taxa de hit/miss do cache Redis.
- `jvm_threads_started_total` -- quantidade de Virtual Threads criadas.

Essas metricas permitem construir dashboards no Grafana que mostram, em tempo real, a saude de cada provedor externo, a eficiencia do cache e o comportamento dos Circuit Breakers.

---

## Como Contribuir

Este projeto foi construido para ser de todos.

O CerneBR nasceu de uma necessidade recorrente: toda empresa brasileira que integra com APIs publicas acaba escrevendo, repetidamente, a mesma logica de retry, cache e fallback. A proposta e resolver isso uma unica vez, de forma robusta, e disponibilizar como infraestrutura aberta.

Precisamos de ajuda. Nao e forca de expressao -- o escopo e grande, o ecossistema de APIs publicas brasileiras e vasto, e as possibilidades de evolucao sao concretas. Se voce e desenvolvedor, testador, redator tecnico ou arquiteto de solucoes, ha espaco para a sua contribuicao.

### Areas onde sua ajuda e essencial

**Novas integracoes de APIs publicas**
O modulo de CEP ja esta implementado com tres provedores e fallback em cascata. Os modulos de CNPJ, IBGE e Feriados estao planejados e precisam de implementacao. Alem deles, ha dezenas de APIs publicas brasileiras que empresas consultam diariamente e que podem ser agregadas: tabelas de NCM, registros da ANVISA, dados do Banco Central, entre outras. Cada nova integracao segue o mesmo padrao arquitetural (interface `CepClientProvider`, Circuit Breaker individual, normalizacao de payload), o que facilita contribuicoes modulares.

**Testes automatizados**
O projeto precisa de cobertura de testes unitarios e de integracao. Testes de contrato para validar o formato das respostas dos provedores, testes de resiliencia para simular falhas e timeouts, e testes de carga para validar o comportamento sob concorrencia. Se voce tem experiencia com JUnit 5, Testcontainers ou Gatling, sua contribuicao sera especialmente valiosa.

**Melhorias de codigo e arquitetura**
Refatoracoes, otimizacoes de performance, revisao de configuracoes do Resilience4j, melhoria dos DTOs, adicao de profiles Spring para diferentes ambientes. Todo pull request que torne o codigo mais limpo, mais testavel ou mais eficiente e bem-vindo.

**Documentacao**
Guias de deploy para Kubernetes (Helm charts), exemplos de integracao com ERPs especificos, tutoriais para configuracao de Grafana com dashboards pre-construidos, traducao da documentacao para ingles. Documentacao de qualidade e o que diferencia um projeto open-source saudavel de um repositorio abandonado.

**Issues e feedback**
Se voce encontrou um bug, tem uma sugestao de melhoria ou quer discutir uma decisao de arquitetura, abra uma issue. Toda perspectiva e relevante -- de quem esta comecando na carreira ate quem lidera equipes de plataforma.

### Como comecar

1. Faca um fork do repositorio.
2. Crie uma branch a partir da `main` (`git checkout -b feature/minha-contribuicao`).
3. Implemente a mudanca com testes.
4. Abra um Pull Request descrevendo o que foi feito e por que.

Nao existe contribuicao pequena. Uma correcao de typo na documentacao, um teste unitario para um edge case, uma sugestao de melhoria em uma issue -- tudo isso move o projeto para frente.

---

## Licenca

Distribuido sob a licenca Apache 2.0. Consulte o arquivo [LICENSE](LICENSE) para os termos completos.

```
Copyright 2026 CerneBR

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
