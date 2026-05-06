# Guia de Contribuição — CerneBR Gateway Nacional

## Visão Geral

Obrigado pelo interesse em contribuir. Este projeto existe com um único propósito: oferecer uma camada de integração **resiliente, observável e enterprise-ready** para o consumo de dados públicos brasileiros (CEP, CNPJ, feriados, e domínios futuros).

A barra de qualidade técnica é alta porque o público-alvo são equipes B2B em produção — tolerância a falha precisa ser comprovada, não suposta. Toda contribuição que mantém ou eleva essa barra é bem-vinda. Este documento descreve o que esperamos para que seu Pull Request seja avaliado e mesclado com o mínimo de fricção.

---

## Ambiente de Desenvolvimento

### Pré-requisitos

| Ferramenta | Versão mínima | Observação |
|---|---|---|
| **JDK** | 25 | Virtual Threads (JEP 444) são parte do contrato de performance do projeto. Versões anteriores não compilam. |
| **Docker** | 24+ | Necessário para subir o Redis local e para a suíte de testes de integração (Testcontainers). |
| **Maven** | — | Não instale. O projeto inclui o wrapper `./mvnw`. Use sempre o wrapper para garantir reprodutibilidade. |
| **Git** | 2.30+ | Padrão moderno; nada exótico. |

### Subindo a infraestrutura local

A aplicação depende de Redis para cache distribuído e para o backend do rate limiter. Para desenvolvimento, suba uma instância efêmera:

```bash
docker run -d --name dev-redis -p 6379:6379 redis:7-alpine
```

Alternativamente, use o `docker-compose.yml` da raiz do projeto, restringindo ao serviço Redis:

```bash
docker compose up -d redis
```

### Compilando e executando

Build completo (compilação + recursos):

```bash
./mvnw clean compile
```

Execução em modo desenvolvimento:

```bash
./mvnw spring-boot:run
```

A aplicação fica disponível em `http://localhost:8080`. O Swagger UI está em `/swagger-ui.html`.

Para gerar o jar executável:

```bash
./mvnw clean package -DskipTests
java -jar target/gateway-nacional-*.jar
```

---

## Testes — O Coração do Projeto

**Pull Requests sem suíte de testes verde não serão analisados.** Esta é uma regra estrita, não uma sugestão. O valor do projeto está na confiabilidade da cascata de fallback, do circuit breaker, do cache e do enriquecimento — todos comportamentos que **só podem ser validados com testes de integração reais**.

### Execução obrigatória antes de qualquer commit

```bash
./mvnw clean verify
```

Esse comando executa:

1. Compilação completa do código de produção e de testes.
2. Toda a suíte de testes unitários e de integração.
3. Validações estáticas configuradas no `pom.xml`.

O comando deve terminar com `BUILD SUCCESS` e a linha `Tests run: N, Failures: 0, Errors: 0`.

### Stack de testes

A confiabilidade dos testes não vem de mocks superficiais — vem da reprodução fidedigna do ambiente:

- **Testcontainers** (`org.testcontainers:junit-jupiter`) sobe um container `redis:7-alpine` real durante a execução. O `CacheManager` do Spring conecta nesse Redis, e o caminho de cache é exercitado de fato. O Redis é provisionado pela classe base `AbstractIntegrationTest` em bloco `static`, garantindo que o port mapping esteja disponível antes da avaliação de `@DynamicPropertySource`.
- **WireMock** (`org.wiremock:wiremock-standalone`) sobe em porta dinâmica e responde no lugar dos provedores externos (ViaCEP, BrasilAPI, ReceitaWS, Nager.Date etc.). As URLs base de cada client externo são externalizadas via `@Value` com defaults para produção, e sobrescritas no `application-test.yml` apontando para `http://localhost:${wiremock.server.port}`.
- **MockMvc** dispara as requisições contra os controllers reais — a request percorre o pipeline completo do Spring (validação, interceptors, cache abstraction, AOP do Resilience4j).

Cada teste roda contra um estado limpo: o `@BeforeEach` em `AbstractIntegrationTest` chama `WIREMOCK.resetAll()` e limpa todos os caches do `CacheManager`. Sem essa limpeza, um `@Cacheable` populado em um teste anterior mascararia o caminho de cascata sob avaliação.

### Escrita de novos testes

Todo novo módulo (ou novo provider em módulo existente) deve incluir, no mínimo:

1. **Teste de caminho feliz**: provider primário responde com sucesso, validação do payload unificado.
2. **Teste de fallback em cascata**: provider primário falha (HTTP 5xx ou timeout), provider secundário responde com sucesso, validação de que o consumidor recebe a resposta correta.

Use o padrão **Given / When / Then** com comentários explícitos delimitando os blocos. Veja `CepCascadeIntegrationTest` como referência canônica.

---

## Padrões de Código e Arquitetura

### Package by Feature

O projeto adota estritamente *Package by Feature*. Cada domínio é um pacote raiz autocontido:

```
br.com.cernebr.gateway_nacional.{cep,cnpj,calendario}
                                  └── client/      (clients HTTP + Anti-Corruption Layer)
                                  └── controller/  (endpoints REST)
                                  └── dto/         (DTO unificado do domínio)
                                  └── service/     (orquestração da cascata)
```

Regras invioláveis:

- **Nenhuma classe do domínio pode vazar para fora do seu pacote raiz.** Se você criar um helper que precisa ser compartilhado entre `cep` e `cnpj`, esse helper pertence a um pacote utilitário (`config`, `exception`) ou deve ser duplicado conscientemente — é raro o caso em que abstrair vale a pena.
- **Não introduza camadas estilo *Package by Layer*** (`controllers/`, `services/`, `repositories/` no nível raiz). A arquitetura é deliberadamente modular para que cada domínio possa ser extraído como microserviço independente no futuro, sem grandes refatorações.

### Anti-Corruption Layer

Toda integração com API externa **deve** isolar o contrato do upstream em um Record interno privado dentro do client. Esse Record:

1. Modela o JSON exato retornado pelo provedor (campos com nomes originais, anotações `@JsonProperty` quando necessário).
2. Usa `@JsonIgnoreProperties(ignoreUnknown = true)` obrigatoriamente — o gateway não pode quebrar quando o upstream adicionar um campo novo.
3. Implementa um método de conversão (`toCepResponse`, `toCnpjResponse`, `toFeriadoResponse`) que retorna o DTO unificado do domínio.

Exemplo canônico em `cnpj/client/ReceitaWsClient.java`:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
private record ReceitaWsPayload(
        String status,
        String cnpj,
        String nome,
        String fantasia,
        String situacao,
        String cep,
        String uf,
        String municipio,
        List<AtividadePrincipal> atividade_principal
) {
    boolean isInvalid() { ... }
    CnpjResponse toCnpjResponse() { ... }
}
```

Quando o ReceitaWS renomear `nome` para `razao_social` na próxima versão da API, **somente o Record `ReceitaWsPayload` muda**. O `CnpjResponse`, o `CnpjService`, o controller, os consumidores externos — todos permanecem inalterados.

**Pull Requests que vazam o schema do upstream para fora do client serão recusados.**

### Tratamento de Exceções nas Cascatas

Não lance `Exception` ou `RuntimeException` genéricas em código de cliente externo. A cascata de resiliência depende de uma exceção semântica única: `ResourceUnavailableException`.

O padrão obrigatório é:

1. **No client**, qualquer falha (HTTP 5xx, timeout, payload vazio, status de erro embutido como `{"status": "ERROR"}`) é convertida em `ResourceUnavailableException` carregando o `providerName`. O fallback do `@CircuitBreaker` faz o mesmo quando o CB está aberto:

   ```java
   @CircuitBreaker(name = "viaCepCB", fallbackMethod = "fallback")
   public CepResponse fetch(String cep) {
       // chamada HTTP, validação de payload
       if (payload == null || payload.isInvalid()) {
           throw new ResourceUnavailableException(PROVIDER_NAME,
                   "ViaCEP retornou resposta vazia ou CEP não localizado.");
       }
       return payload.toCepResponse();
   }

   private CepResponse fallback(String cep, Throwable cause) {
       throw new ResourceUnavailableException(PROVIDER_NAME,
               "ViaCEP indisponível ou Circuit Breaker aberto.", cause);
   }
   ```

2. **No service**, o loop de orquestração captura `Exception` (deliberadamente amplo, para incluir falhas de runtime imprevistas) e cascateia para o próximo provider. Quando todos os providers falham, o service lança a `ResourceUnavailableException` final com `providerName = "all-providers"`:

   ```java
   for (CepClientProvider provider : providersInOrder) {
       try {
           CepResponse raw = provider.fetch(cep);
           // ...
           return enriched;
       } catch (Exception ex) {
           log.warn("Provider {} failed: {}. Cascading.", provider.providerName(), ex.getMessage());
       }
   }
   throw new ResourceUnavailableException("all-providers",
           "Todos os provedores de CEP falharam após o fallback em cascata.");
   ```

3. **No `GlobalExceptionHandler`**, `ResourceUnavailableException` é mapeada para HTTP 503 com `ProblemDetail` (RFC 7807), incluindo `traceId` para correlação. Stacktraces nunca vazam ao cliente.

Esse contrato é o que garante a previsibilidade do comportamento sob falha. Qualquer desvio quebra a observabilidade e a confiabilidade do produto.

### Métricas

Todo novo provider deve emitir as métricas `gateway.provider.requests` (counter) e `gateway.provider.latency` (timer), com tags `domain`, `provider` e `outcome`. Use o helper `recordOutcome` já existente nos services como referência. A normalização da tag `provider` é `providerName.toLowerCase(Locale.ROOT)`.

### Configuração

Valores configuráveis (URLs base, timeouts, TTLs, flags) devem ser expostos via `application.yml` com namespace coerente (`gateway.<dominio>.<provider>.<propriedade>`) e injetados via `@Value` com **default seguro de produção**. Isso é essencial para que os testes possam sobrescrever as URLs sem patches estruturais no código.

---

## Padrão de Commits

Adotamos [Conventional Commits 1.0](https://www.conventionalcommits.org/pt-br/v1.0.0/) sem exceções. Toda mensagem de commit deve seguir o formato:

```
<tipo>(<escopo opcional>): <descrição imperativa em minúsculas>

<corpo opcional explicando o porquê>

<rodapé opcional para breaking changes ou referências a issues>
```

Tipos aceitos:

| Tipo | Uso |
|---|---|
| `feat` | Nova funcionalidade visível ao usuário ou nova capacidade arquitetural. |
| `fix` | Correção de bug. Inclua referência à issue no corpo quando aplicável. |
| `refactor` | Mudança estrutural sem alteração de comportamento observável. |
| `docs` | Alterações apenas em documentação (README, CONTRIBUTING, Javadoc, comentários). |
| `test` | Adição ou correção de testes. Não use para mudanças que também alteram código de produção. |
| `chore` | Manutenção de build, dependências, configuração de CI. |
| `perf` | Otimização de performance comprovada por medição. |

Exemplos válidos:

```
feat(calendario): suportar feriados estaduais via parâmetro siglaUf
fix(cnpj): tratar resposta {"status":"ERROR"} do ReceitaWS como falha
refactor(cep): extrair normalização de UF para helper compartilhado
docs(readme): adicionar query Prometheus de degradação do calculador offline
```

Mensagens vagas (`update code`, `fix bug`, `wip`) serão recusadas no review.

---

## Processo de Pull Request

1. **Fork** o repositório para sua conta.

2. **Crie uma branch** com nome semântico, prefixada pelo tipo do trabalho:

   ```bash
   git checkout -b feat/calendario-suporte-municipal
   git checkout -b fix/cnpj-receita-ws-status-error
   git checkout -b refactor/extrair-anti-corruption-layer-base
   ```

3. **Implemente a mudança** seguindo os padrões descritos acima. Inclua testes de integração para qualquer comportamento novo ou alterado.

4. **Execute a suíte completa** localmente:

   ```bash
   ./mvnw clean verify
   ```

5. **Faça commits atômicos** seguindo o padrão Conventional Commits. Prefira commits pequenos e focados a um commit gigante de "tudo de uma vez" — facilita o review e o `git bisect` futuro.

6. **Abra o Pull Request** descrevendo, no mínimo:

   - **Problema** que está sendo resolvido (com link para issue, se houver).
   - **Solução adotada** e por que foi escolhida em vez de alternativas consideradas.
   - **Plano de teste**: como o revisor pode validar manualmente, além da suíte automatizada.
   - **Impacto em métricas, contratos públicos ou breaking changes**, se aplicável.

7. **Responda aos comentários do review** com pushes adicionais à mesma branch. Não force-push depois que o review começou — isso reescreve o histórico e dificulta o acompanhamento das mudanças. Quando a branch estiver pronta para merge, o mantenedor fará o squash final.

### Critérios de aceitação

Um Pull Request será mesclado quando:

- Todos os checks de CI estiverem verdes.
- Pelo menos um mantenedor aprovar o review.
- Os pontos arquiteturais deste documento forem cumpridos.
- A descrição do PR estiver completa e a ligação entre problema e solução for clara.

Pull Requests parados sem resposta a comentários por mais de 30 dias serão fechados — sem prejuízo de reabertura quando o autor retomar o trabalho.

---

Obrigado por dedicar seu tempo para elevar a qualidade deste projeto. Cada contribuição cuidadosa torna a infraestrutura de dados públicos brasileiros um pouco mais previsível para todo mundo.
