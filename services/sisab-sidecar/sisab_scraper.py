"""
SISAB validation report scraper — headless-Chromium Selenium driver.

Adapted from the AutoAPSFinancias proven-in-production implementation
(/src/extract/sisab.py). Returns parsed rows as a list of dicts instead
of writing to a database — the gateway sidecar pattern serves the data
back to the Java caller as JSON.

Why a Python sidecar instead of HTTP-pure replication in Java
=============================================================
The SISAB validation page is a JSF/Mojarra application whose filter
controls (validacao, colunas, competencia) are rendered by Bootstrap
Multiselect plugins. Those plugins keep their state in client-side
DOM (checkboxes inside <ul> dropdowns) and only sync it back to the
underlying <select multiple> when their JavaScript fires. A direct
HTTP POST therefore submits a form whose <select multiple> elements
are empty, and the Mojarra backend trips on
java.lang.IndexOutOfBoundsException trying to read the first element
of an empty list. Replicating the Bootstrap Multiselect behavior
byte-by-byte from a JVM HTTP client is fragile and breaks on every
upstream JS bump. Headless Chromium is the only reliable approach,
and FlareSolverr's request.post API does not expose JS interaction
primitives (executeJS, click, waitFor). Hence: a dedicated Python
sidecar that owns the browser, exposes a REST endpoint, and is
driven by the Java gateway as a regular upstream provider.
"""

from __future__ import annotations

import os
import time
import unicodedata
from dataclasses import dataclass, field
from io import StringIO
from shutil import which
from typing import Any

import pandas as pd
from selenium import webdriver
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait
from webdriver_manager.chrome import ChromeDriverManager


SISAB_URL = (
    "https://sisab.saude.gov.br/paginas/acessoRestrito/"
    "relatorio/federal/envio/RelValidacao.xhtml"
)


@dataclass
class SisabResult:
    """Outcome of a single SISAB scrape."""

    rows: list[dict[str, Any]] = field(default_factory=list)
    competencia: str = ""
    """Competencia normalised to MM/AAAA, echoed back for caller traceability."""

    empty: bool = False
    """True when the SISAB explicitly answered "Nenhum registro correspondente"."""


# --------------------------------------------------------------------------- #
# Helpers (mirroring AutoAPSFinancias /src/extract/sisab.py — see header).    #
# --------------------------------------------------------------------------- #
def _achar_elemento(wait, seletores, clicavel: bool = False):
    """Try a list of fallback selectors until one matches."""
    ultimo_erro = None
    for by, value in seletores:
        try:
            if clicavel:
                return wait.until(EC.element_to_be_clickable((by, value)))
            return wait.until(EC.presence_of_element_located((by, value)))
        except Exception as exc:  # noqa: BLE001 — propagate the last exception
            ultimo_erro = exc
    if ultimo_erro:
        raise ultimo_erro
    raise TimeoutException("Nenhum seletor informado encontrou elemento.")


def _clicar(wait, driver, seletores, descricao):
    """Click with a JS fallback when the native click is blocked."""
    elemento = _achar_elemento(wait, seletores, clicavel=True)
    try:
        elemento.click()
    except Exception:  # noqa: BLE001
        driver.execute_script("arguments[0].click();", elemento)


def _normalizar_texto(valor):
    if valor is None:
        return ""
    texto = unicodedata.normalize("NFKD", str(valor))
    texto = "".join(ch for ch in texto if not unicodedata.combining(ch))
    return " ".join(texto.strip().upper().split())


def _normalizar_competencia_mm_aaaa(valor: str) -> str:
    """Accepts ``MM/AAAA`` (UI form) or ``yyyy-MM`` (API form) — returns ``MM/AAAA``.

    The Java gateway exposes the API contract as ``yyyy-MM`` (e.g. ``2026-02``);
    the SISAB page itself displays ``MM/AAAA`` (e.g. ``02/2026``). This
    function bridges both shapes so the sidecar can be called either way.
    """
    texto = str(valor or "").strip()
    if "-" in texto and len(texto) == 7:
        ano, mes = texto.split("-", 1)
        return f"{int(mes):02d}/{int(ano):04d}"
    partes = texto.split("/")
    if len(partes) != 2:
        raise ValueError(f"Competencia invalida: {valor!r}. Use MM/AAAA ou yyyy-MM.")
    mes = int(partes[0])
    ano = int(partes[1])
    if mes < 1 or mes > 12:
        raise ValueError(f"Mes invalido na competencia: {valor!r}.")
    return f"{mes:02d}/{ano:04d}"


def _aguardar_ajax(driver, timeout: int = 30):
    """Block until ``document.readyState === 'complete'`` and ``jQuery.active === 0``.

    PrimeFaces fires partial-AJAX during cascade, and reading the DOM mid-flight
    yields stale state. Polling these two conditions is the same idiom the
    legacy AutoAPSFinancias scraper uses; it has proven robust in production.
    """
    fim = time.time() + timeout
    while time.time() < fim:
        try:
            pronto = driver.execute_script("return document.readyState") == "complete"
            jquery_ok = driver.execute_script(
                "return (typeof jQuery === 'undefined') ? true : (jQuery.active === 0);"
            )
            if pronto and jquery_ok:
                return
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.2)
    raise TimeoutException("Timeout aguardando fim de requisicoes AJAX.")


def _selecionar_select_por_texto(wait, driver, seletores, texto_alvo, descricao):
    """Set a vanilla ``<select>`` by visible text (accent/case-insensitive)."""
    select_elem = _achar_elemento(wait, seletores, clicavel=False)
    alvo_norm = _normalizar_texto(texto_alvo)

    ok = driver.execute_script(
        r"""
        const selectEl = arguments[0];
        const alvo = arguments[1];
        const norm = (s) => (s || '')
          .normalize('NFD')
          .replace(/[̀-ͯ]/g, '')
          .replace(/\s+/g, ' ')
          .trim()
          .toUpperCase();

        let found = false;
        for (const opt of selectEl.options) {
          if (norm(opt.textContent) === alvo || norm(opt.textContent).includes(alvo)) {
            selectEl.value = opt.value;
            found = true;
            break;
          }
        }

        if (found) {
          selectEl.dispatchEvent(new Event('change', { bubbles: true }));
          selectEl.dispatchEvent(new Event('input', { bubbles: true }));
        }
        return found;
        """,
        select_elem,
        alvo_norm,
    )

    if not ok:
        raise TimeoutException(f"Opcao '{texto_alvo}' nao encontrada em {descricao}.")

    _aguardar_ajax(driver, timeout=30)


def _selecionar_multiselect_select_por_valor(wait, driver, seletores, valor_alvo, descricao):
    """Set a ``<select multiple>`` to the option whose ``value`` matches.

    Used for picking the município by IBGE code — the option labels carry the
    accented Portuguese name and the IBGE numeric value, so matching by value
    is both unambiguous and avoids Unicode pitfalls.
    """
    select_elem = _achar_elemento(wait, seletores, clicavel=False)
    valor_alvo = str(valor_alvo).strip()

    ok = driver.execute_script(
        """
        const selectEl = arguments[0];
        const alvo = arguments[1];

        let found = false;
        for (const opt of selectEl.options) {
            const match = (opt.value || '').trim() === alvo;
            opt.selected = match;
            if (match) found = true;
        }

        if (found) {
            selectEl.dispatchEvent(new Event('change', { bubbles: true }));
            selectEl.dispatchEvent(new Event('input', { bubbles: true }));
        }
        return found;
        """,
        select_elem,
        valor_alvo,
    )

    if not ok:
        raise TimeoutException(f"IBGE '{valor_alvo}' nao encontrado em {descricao}.")

    _aguardar_ajax(driver, timeout=30)


def _aguardar_resultado_sisab(driver, timeout: int = 120) -> str:
    """Wait for either the result table or the explicit empty-set message."""
    fim = time.time() + timeout
    while time.time() < fim:
        try:
            if driver.find_elements(By.ID, "tabela"):
                return "tabela"
            texto = driver.find_element(By.TAG_NAME, "body").text
            if "Nenhum registro correspondente" in texto:
                return "vazio"
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.5)
    raise TimeoutException("Timeout aguardando retorno da tabela do SISAB.")


def _marcar_multiselect_por_texto(wait, driver, container_xpath, texto_alvo, descricao):
    """Open a Bootstrap Multiselect dropdown and check the matching item.

    These widgets render visually as buttons + checkbox lists; the underlying
    ``<select>`` is hidden. Clicking the visible checkbox is what triggers the
    plugin's ``.refresh()`` call, which is the *only* path that syncs the
    selection back to the hidden ``<select>``. Any HTTP-only client misses
    that sync and the form arrives with empty selects → IndexOutOfBoundsException.
    """
    _clicar(
        wait,
        driver,
        [(By.XPATH, f"{container_xpath}//button[contains(@class,'dropdown-toggle')]")],
        f"Abrir {descricao}",
    )

    alvo_norm = _normalizar_texto(texto_alvo)
    itens = wait.until(
        EC.presence_of_all_elements_located(
            (By.XPATH, f"{container_xpath}//ul[contains(@class,'multiselect-container')]//label")
        )
    )

    marcado = False
    for item in itens:
        texto = _normalizar_texto(item.text)
        if texto == alvo_norm or alvo_norm in texto:
            input_el = item.find_element(By.XPATH, ".//input[@type='checkbox']")
            if not input_el.is_selected():
                driver.execute_script("arguments[0].click();", input_el)
            marcado = True
            break

    if not marcado:
        raise TimeoutException(f"Opcao '{texto_alvo}' nao encontrada em {descricao}.")

    driver.execute_script("document.body.click();")


def _forcar_100_registros_por_pagina(driver):
    """DataTables default is 10 rows/page; force 100 to minimise pagination loops."""
    try:
        mudou = driver.execute_script(
            """
            const sel = document.querySelector("select[name='tabela_length']");
            if (!sel) return false;
            const values = Array.from(sel.options).map(o => o.value);
            if (values.includes('100')) {
                sel.value = '100';
            } else {
                sel.value = sel.options[sel.options.length - 1].value;
            }
            sel.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
            """
        )
        if mudou:
            _aguardar_ajax(driver, timeout=30)
            time.sleep(1)
    except Exception:  # noqa: BLE001
        pass


def _coletar_tabela_sisab_completa(driver, wait) -> pd.DataFrame:
    """Walk every page of the DataTable and consolidate into one DataFrame."""
    _forcar_100_registros_por_pagina(driver)

    paginas = []
    pagina_idx = 1
    max_paginas = 300

    while pagina_idx <= max_paginas:
        tabela = _achar_elemento(wait, [(By.ID, "tabela")], clicavel=False)
        html_tabela = tabela.get_attribute("outerHTML")
        df_pagina = pd.read_html(StringIO(html_tabela))[0]
        paginas.append(df_pagina)

        try:
            prox_li = driver.find_element(By.ID, "tabela_next")
        except NoSuchElementException:
            break

        classes = (prox_li.get_attribute("class") or "").lower()
        if "disabled" in classes:
            break

        info_antes = ""
        try:
            info_antes = driver.find_element(By.ID, "tabela_info").text.strip()
        except Exception:  # noqa: BLE001
            pass

        try:
            link_proximo = prox_li.find_element(By.TAG_NAME, "a")
            driver.execute_script("arguments[0].click();", link_proximo)
        except Exception:  # noqa: BLE001
            break

        _aguardar_ajax(driver, timeout=30)
        try:
            WebDriverWait(driver, 30).until(
                lambda d: (d.find_element(By.ID, "tabela_info").text.strip() != info_antes)
                or ("disabled" in (d.find_element(By.ID, "tabela_next").get_attribute("class") or "").lower())
            )
        except Exception:  # noqa: BLE001
            pass

        pagina_idx += 1

    if not paginas:
        return pd.DataFrame()

    df_total = pd.concat(paginas, ignore_index=True)
    return df_total.drop_duplicates()


def _criar_driver_chrome(chrome_options) -> webdriver.Chrome:
    chrome_bin = os.getenv("CHROME_BIN")
    if chrome_bin:
        chrome_options.binary_location = chrome_bin

    chromedriver_path = os.getenv("CHROMEDRIVER_PATH") or which("chromedriver")
    if chromedriver_path and os.path.exists(chromedriver_path):
        return webdriver.Chrome(service=Service(chromedriver_path), options=chrome_options)

    # Last-resort: download a matching chromedriver via webdriver-manager. In
    # the container image we always set CHROMEDRIVER_PATH, so this path only
    # exercises in local dev runs.
    return webdriver.Chrome(service=Service(ChromeDriverManager().install()), options=chrome_options)


# --------------------------------------------------------------------------- #
# Public entrypoint                                                           #
# --------------------------------------------------------------------------- #
def scrape_sisab(uf: str, ibge: str, competencia: str) -> SisabResult:
    """Run the full SISAB cascade and return the parsed validation rows.

    Parameters
    ----------
    uf : str
        Two-letter state code (e.g. ``"BA"``). Case-insensitive.
    ibge : str
        Six-digit IBGE municipality code (e.g. ``"291710"``).
    competencia : str
        Either ``"MM/AAAA"`` (e.g. ``"02/2026"``) or ``"yyyy-MM"``
        (e.g. ``"2026-02"``).

    Returns
    -------
    SisabResult
        ``rows`` populated when the report contained data; ``empty=True`` when
        SISAB explicitly answered "Nenhum registro correspondente"; raises
        :class:`TimeoutException` or :class:`ValueError` on contract drift
        / invalid input.
    """
    competencia_norm = _normalizar_competencia_mm_aaaa(competencia)

    chrome_options = Options()
    chrome_options.add_argument("--headless=new")
    chrome_options.add_argument("--no-sandbox")
    chrome_options.add_argument("--disable-dev-shm-usage")
    chrome_options.add_argument("--window-size=1920,1080")
    chrome_options.add_argument("--disable-gpu")
    chrome_options.add_argument("--lang=pt-BR")

    driver = _criar_driver_chrome(chrome_options)

    try:
        driver.get(SISAB_URL)
        wait = WebDriverWait(driver, 35)
        wait.until(lambda d: d.execute_script("return document.readyState") == "complete")

        # Step 1 — Unidade Geográfica = Município
        _selecionar_select_por_texto(
            wait, driver,
            [(By.ID, "unidGeo"), (By.NAME, "unidGeo")],
            "Municipio", "Unidade Geografica",
        )
        _aguardar_ajax(driver, timeout=35)

        # Step 2 — UF (renders into #regioes after the cascade fires)
        wait.until(EC.presence_of_all_elements_located((By.XPATH, "//span[@id='regioes']//select")))
        _selecionar_select_por_texto(
            wait, driver,
            [
                (By.ID, "estadoMunicipio"),
                (By.XPATH, "(//span[@id='regioes']//select)[1]"),
            ],
            uf.upper(), "UF",
        )

        # Step 3 — wait for the município list to populate, then pick by IBGE.
        # Headless Chromium is occasionally slow to fire the change event;
        # the fallback re-dispatches the event manually before waiting again.
        try:
            WebDriverWait(driver, 60).until(
                lambda d: d.execute_script(
                    """
                    const sel = document.querySelector('#municipios');
                    return !!sel && sel.options && sel.options.length > 1;
                    """
                )
            )
        except TimeoutException:
            driver.execute_script(
                """
                const uf = document.querySelector('#estadoMunicipio');
                if (uf) {
                  uf.dispatchEvent(new Event('change', { bubbles: true }));
                  if (typeof uf.onchange === 'function') uf.onchange();
                }
                """
            )
            _aguardar_ajax(driver, timeout=30)
            WebDriverWait(driver, 60).until(
                lambda d: d.execute_script(
                    """
                    const sel = document.querySelector('#municipios');
                    return !!sel && sel.options && sel.options.length > 1;
                    """
                )
            )

        _selecionar_multiselect_select_por_valor(
            wait, driver,
            [
                (By.ID, "municipios"),
                (By.XPATH, "//span[@id='regioes']//select[@multiple]"),
            ],
            ibge.strip(), "Municipio (IBGE)",
        )

        # Step 4 — "Marcar todas" no Bootstrap Multiselect das colunas.
        _clicar(
            wait, driver,
            [(By.XPATH, "//span[@id='filtrosLinhaColunaRelatorio']//button[contains(@class,'dropdown-toggle')]")],
            "Abrir seletor de colunas",
        )
        _clicar(
            wait, driver,
            [(By.XPATH, "//span[@id='filtrosLinhaColunaRelatorio']//li[contains(@class,'multiselect-all')]//input[@type='checkbox']")],
            "Marcar todas as colunas",
        )
        driver.execute_script("document.body.click();")

        # Step 5 — Filtro Validação: Aprovado. This is the crucial Bootstrap
        # Multiselect whose backing <select> stays empty without a real DOM
        # click; missing it is what causes the IndexOutOfBoundsException seen
        # in HTTP-only clients.
        _marcar_multiselect_por_texto(
            wait, driver,
            "//span[@id='validacao']",
            "Aprovado", "Validacao",
        )

        # Step 6 — Competência (MM/AAAA visible label)
        _selecionar_select_por_texto(
            wait, driver,
            [(By.XPATH, "//span[@id='competencia']//select")],
            competencia_norm, "Competencia",
        )

        # Step 7 — Submit "Ver em tela"
        _clicar(
            wait, driver,
            [
                (By.CSS_SELECTOR, "label[for='verTela']"),
                (By.ID, "verTela"),
            ],
            "Gerar em tela",
        )
        _aguardar_ajax(driver, timeout=60)
        _forcar_100_registros_por_pagina(driver)

        status = _aguardar_resultado_sisab(driver, timeout=120)
        if status == "vazio":
            return SisabResult(rows=[], competencia=competencia_norm, empty=True)

        df = _coletar_tabela_sisab_completa(driver, wait)
        if df.empty:
            return SisabResult(rows=[], competencia=competencia_norm, empty=True)

        # Drop the spurious "Unnamed:*" columns Pandas attaches when the upstream
        # HTML has empty <th>; they carry no signal.
        df = df.loc[:, ~df.columns.str.contains("^Unnamed")]

        # NaN serialises poorly across the wire; convert to None then to dicts.
        df = df.where(pd.notnull(df), None)

        return SisabResult(
            rows=df.to_dict("records"),
            competencia=competencia_norm,
            empty=False,
        )
    finally:
        driver.quit()
