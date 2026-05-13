# CerneBR — Gateway Nacional

> **A infraestrutura definitiva e self-hosted para consumo de dados públicos brasileiros.**
> Latência mínima, resiliência industrial e zero acoplamento com a instabilidade da internet pública.

[![Build](https://img.shields.io/badge/build-passing-brightgreen)](#)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange)](#)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F)](#)
[![Docker Ready](https://img.shields.io/badge/docker-ready-2496ED)](#)
[![Tests](https://img.shields.io/badge/tests-Testcontainers%20%2B%20WireMock-25D366)](#)

> [!IMPORTANT]
> 📚 **A documentação completa de APIs, rotas e payloads interativos está disponível no portal oficial: https://cernebr.dev.br**

---

## O Problema

Toda empresa brasileira que constrói software já lidou com rate limits agressivos, quedas em cascata de provedores públicos (ViaCEP, ReceitaWS, IBGE), formatos divergentes de dados e latência imprevisível.

O **Gateway Nacional** é um microserviço self-hosted que **blinda sua aplicação** dessas patologias. Você consulta um único endpoint local, e toda a complexidade desaparece.

## Visão Geral da Arquitetura e Core Engines

O Gateway Nacional atua como um agregador de altíssima performance, projetado com tolerância zero a latência e dependência externa. Cada decisão arquitetural é orientada a throughput e disponibilidade:

- **HedgedExecutor (Requisições Paralelas)**: Em vez de tentar um provedor de cada vez, o gateway dispara requisições simultâneas para múltiplos provedores (ex: ViaCEP, BrasilAPI e AwesomeAPI). O primeiro a responder vence, e as requisições lentas são canceladas (Hedged Request), garantindo a menor latência de rede possível na ponta.
- **RefreshAheadCache (RAC)**: TTL inteligente que revalida dados quentes em background (Virtual Threads) antes que o cache expire. O cliente sempre obtém a resposta em sub-milissegundos da memória, enquanto a atualização ocorre de forma assíncrona.
- **Cascata Sequencial de Fallbacks**: Circuit Breakers (`Resilience4j`) isolados por upstream. A queda de um provedor primário aciona instantaneamente o próximo da fila, sem vazar a indisponibilidade para a aplicação cliente.
- **Java 25 + Virtual Threads**: I/O bloqueante virtualizado para milhares de requisições concorrentes com footprint de memória mínimo.

---

## Guia Rápido de Self-Host / Local Setup

Para rodar o Gateway Nacional localmente ou realizar o deploy em sua infraestrutura, siga os pré-requisitos e instruções abaixo.

### Pré-requisitos
- **JDK 25** (Recomendado)
- **Docker e Docker Compose** (Para serviços auxiliares como Redis e Sidecars)
- **Redis** (Cache distribuído)

### Rodando via Docker (Recomendado)
A stack inteira sobe em um único comando (Aplicação + Redis + Sidecars de Web Scraping para instâncias governamentais como FIPE e Saúde):

```bash
docker compose up -d --build
```
A aplicação estará disponível em `http://localhost:8080`.

### Rodando Localmente (Desenvolvimento)
Para rodar nativamente via Maven, inicie um Redis local (ex: porta `6379`) e execute:

```bash
./mvnw spring-boot:run
```

## Dimensionamento e Consumo de Servidor (RAM)

O Gateway Nacional foi projetado para ser leve (utilizando Java 25 + Virtual Threads), mas o seu consumo total de memória depende de quais módulos você deseja manter ativos, especialmente os **Sidecars de Web Scraping**.

### Cenário 1: Stack Completa (Recomendado) — **Mínimo: 2GB de RAM**
Se você deseja utilizar **todas** as rotas, incluindo o **Scraper Oficial da FIPE** e as rotas complexas de **Saúde (SISAB, CNES)**, o sistema sobe com os sidecars `FlareSolverr` e `SISAB-Sidecar`.
- **API Java + Redis**: ~400MB a 512MB
- **FlareSolverr (Chromium)**: ~500MB
- **SISAB Sidecar (Selenium)**: ~600MB
- **Total Necessário**: Servidor ou VM com pelo menos **2GB a 3GB de RAM** para evitar *Out of Memory (OOM)* em picos de requisições.

### Cenário 2: Modo Leve (Apenas REST) — **Mínimo: 1GB de RAM**
Ideal para empresas locais que vão consumir primariamente rotas como **CEP, CNPJ, Bancos, IBGE, NCM, e Taxas.**
Nesse modo, você economiza muita memória desligando os navegadores headless em background. A rota da FIPE continuará funcionando através do motor de *Cascata de Fallbacks* (utilizando provedores REST secundários como BrasilAPI e Parallelum).

**Como ativar o Modo Leve (Desativar Scraping e Sidecars):**
1. No arquivo `docker-compose.yml`, **comente** ou apague os serviços `flaresolverr` e `sisab-sidecar`.
2. No mesmo arquivo, certifique-se de que as variáveis do serviço `api` estejam vazias:
   ```yaml
   GATEWAY_FLARESOLVERR_URL: ""
   GATEWAY_SISAB_SIDECAR_URL: ""
   ```
O Gateway detecta a ausência do sidecar e faz o "curto-circuito" inteligentemente, pulando o scraping pesado e roteando os pedidos para os fallbacks leves via rede, mantendo o consumo total abaixo de **512MB**.

---

### Configuração (`application.yml` ou `.env`)

As principais variáveis de ambiente incluem chaves de provedores de terceiros (quando aplicável) e configurações de infraestrutura:

| Variável | O que faz |
|---|---|
| `SPRING_DATA_REDIS_HOST` | Host do Redis (padrão: `localhost`). |
| `SPRING_DATA_REDIS_PORT` | Porta do Redis (padrão: `6379`). |
| `GATEWAY_RATE_LIMIT_ENABLED`| Ativa Rate Limiting do Bucket4j (padrão: `false` para tráfego ilimitado). |
| `GATEWAY_FLARESOLVERR_URL` | URL do sidecar FlareSolverr (ex: `http://localhost:8191`). Essencial para rotas de Web Scraping e resoluções com CAPTCHAs (FIPE, Saúde, Placa). |
| `GATEWAY_SISAB_SIDECAR_URL` | URL do sidecar SISAB (Selenium). Necessário para integração específica com o portal do DATASUS. |
| `GATEWAY_PLACA_KEPLACA_TOKEN`| Opcional: Token para fallback de consulta de Placas Veiculares via provedor Keplaca. |

---

<div align="center">
  Produzido por <a href="https://cernebr.dev.br">CerneBR</a>
</div>
