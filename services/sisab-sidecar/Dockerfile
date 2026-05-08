# Multi-stage não economiza muito aqui (Chrome em si pesa ~300 MB), então
# usamos uma única camada. Base é python:3.11-slim para reduzir footprint.
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DEBIAN_FRONTEND=noninteractive \
    CHROME_BIN=/usr/bin/google-chrome \
    CHROMEDRIVER_PATH=/usr/local/bin/chromedriver

# 1) Pacotes runtime mínimos para o Chrome (libs gráficas, fontes, etc.).
#    Lista enxuta — copiada de quê o Chromium em modo headless realmente abre,
#    não a lista padrão "kitchen sink" que a Google publica.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    gnupg \
    unzip \
    fonts-liberation \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libcairo2 \
    libcups2 \
    libdbus-1-3 \
    libdrm2 \
    libgbm1 \
    libglib2.0-0 \
    libgtk-3-0 \
    libnspr4 \
    libnss3 \
    libpango-1.0-0 \
    libx11-6 \
    libx11-xcb1 \
    libxcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxrandr2 \
    libxshmfence1 \
    xdg-utils \
    && rm -rf /var/lib/apt/lists/*

# 2) Google Chrome estável — dpkg ao invés de apt para sobrescrever quaisquer
#    libs antigas remanescentes do passo anterior.
RUN curl -fsSL https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb -o /tmp/chrome.deb \
    && apt-get update \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm /tmp/chrome.deb \
    && rm -rf /var/lib/apt/lists/*

# 3) ChromeDriver com versão *exatamente* casada à do Chrome instalado.
#    Os endpoints "for-testing" do Chrome publicam binários por versão major,
#    o que evita drift entre Chrome e driver — fonte recorrente de quebra
#    silenciosa em headless.
RUN CHROME_VERSION=$(google-chrome --version | awk '{print $3}') \
    && CHROME_MAJOR=$(echo "$CHROME_VERSION" | cut -d. -f1) \
    && DRIVER_VERSION=$(curl -fsSL "https://googlechromelabs.github.io/chrome-for-testing/LATEST_RELEASE_${CHROME_MAJOR}") \
    && curl -fsSL "https://storage.googleapis.com/chrome-for-testing-public/${DRIVER_VERSION}/linux64/chromedriver-linux64.zip" -o /tmp/chromedriver.zip \
    && unzip -q /tmp/chromedriver.zip -d /tmp \
    && mv /tmp/chromedriver-linux64/chromedriver /usr/local/bin/chromedriver \
    && chmod +x /usr/local/bin/chromedriver \
    && rm -rf /tmp/chromedriver.zip /tmp/chromedriver-linux64

# 4) Dependências Python — copiadas antes do código para tirar proveito do
#    layer cache do Docker quando só o código mudar.
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 5) Código da aplicação.
COPY sisab_scraper.py app.py ./

# 6) Usuário não-root — Chrome se recusa a rodar como root em algumas
#    distros (--no-sandbox contorna, mas execução como user é mais higiênica).
RUN useradd --create-home --shell /bin/bash sidecar && chown -R sidecar:sidecar /app
USER sidecar

EXPOSE 8000

# Healthcheck de baixo overhead — endpoint /health responde em <5 ms,
# o docker-compose pode usar este sinal pra ordenar dependências.
HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=15s \
    CMD curl -fsS http://localhost:8000/health || exit 1

# uvicorn com 1 worker — Selenium serializa ao Chrome de qualquer forma,
# múltiplos workers só consumiriam mais RAM sem ganho de throughput real.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
